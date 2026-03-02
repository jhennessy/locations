# Self-Hosted Runner Deployment

## Goal

Replace the current GHCR image build + Watchtower deployment with an in-container GitHub Actions self-hosted runner. A single container runs both the app and a GitHub Actions runner, managed by supervisord. Pushes to `main` trigger a workflow that runs inside the container — git pull, conditional pip install, supervisorctl restart. Deploys take seconds instead of minutes with no image rebuild.

## Architecture

```
Container (ghcr.io/jhennessy/locations-runner:latest)
├── /runner          # GitHub Actions runner binary (baked into image)
├── /app             # Git clone of the repo (cloned at first boot by entrypoint)
│   └── server/      # Python app code
├── /data            # Mounted from host — persistent (DB, logs)
└── supervisord      # Manages two processes:
    ├── [program:app]    → python main.py (in /app/server)
    └── [program:runner] → /runner/run.sh
```

- `/runner` and `/app` are separate filesystem paths
- `/data` is a host-mounted volume (`./data:/data`) for persistent data (SQLite DB, logs)
- On deploy, the DB is backed up as `/data/locations-backup-{short_sha}.db` (keeps last 5)

## Reference Implementation

These files are taken from the herdie project where this is already working. Adapt them for locations.

### deploy/Dockerfile

```dockerfile
FROM python:3.12-slim

ARG RUNNER_VERSION=2.322.0
ARG RUNNER_ARCH=x64

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl git jq sudo sqlite3 supervisor libicu-dev \
    && rm -rf /var/lib/apt/lists/*

# Create runner user (uid 1000) with sudo for pip only
RUN useradd -m -u 1000 -s /bin/bash runner \
    && echo "runner ALL=(root) NOPASSWD: /usr/local/bin/pip, /usr/local/bin/pip3" > /etc/sudoers.d/runner \
    && chmod 0440 /etc/sudoers.d/runner

# Install GitHub Actions runner into /runner
RUN mkdir -p /runner && cd /runner \
    && curl -fsSL "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz" \
       | tar xz \
    && chown -R runner:runner /runner \
    && ./bin/installdependencies.sh

# App directory (repo cloned here at runtime by entrypoint)
RUN mkdir -p /app && chown runner:runner /app

# Data directory (mounted volume)
RUN mkdir -p /data && chown runner:runner /data

COPY supervisord.conf /etc/supervisor/supervisord.conf
COPY entrypoint.sh /entrypoint.sh
COPY deploy.sh /deploy/deploy.sh
RUN chmod +x /entrypoint.sh /deploy/deploy.sh

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
```

### deploy/supervisord.conf

Note: the app runs on port 8380 internally (see main.py). The docker-compose maps 8080→8380.

```ini
[unix_http_server]
file=/var/run/supervisor.sock
chmod=0770
chown=root:runner

[supervisord]
nodaemon=true
logfile=/data/supervisord.log
logfile_maxbytes=10MB
logfile_backups=3
pidfile=/var/run/supervisord.pid

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface

[supervisorctl]
serverurl=unix:///var/run/supervisor.sock

[program:app]
command=python main.py
directory=/app/server
user=runner
autostart=true
autorestart=true
startretries=5
startsecs=5
stdout_logfile=/data/app.log
stdout_logfile_maxbytes=10MB
stdout_logfile_backups=3
stderr_logfile=/data/app-error.log
stderr_logfile_maxbytes=10MB
stderr_logfile_backups=3
environment=HOME="/home/runner"

[program:runner]
command=/runner/run.sh
directory=/runner
user=runner
autostart=true
autorestart=true
startretries=5
startsecs=5
stdout_logfile=/data/runner.log
stdout_logfile_maxbytes=10MB
stdout_logfile_backups=3
stderr_logfile=/data/runner-error.log
stderr_logfile_maxbytes=10MB
stderr_logfile_backups=3
environment=HOME="/home/runner"
```

### deploy/entrypoint.sh

Adapt for locations:
- Default RUNNER_NAME should be `locations-runner`
- Required env vars: `GITHUB_PAT`, `GITHUB_REPO_URL`, `SECRET_KEY`
- The pip install filters out pytest and httpx (matching current Dockerfile behaviour: `grep -vE 'pytest|httpx' requirements.txt`)

```bash
#!/usr/bin/env bash
set -euo pipefail

# --- Validate required env vars ---
missing=()
for var in GITHUB_PAT GITHUB_REPO_URL SECRET_KEY; do
    if [ -z "${!var:-}" ]; then
        missing+=("$var")
    fi
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: Missing required env vars: ${missing[*]}" >&2
    exit 1
fi

RUNNER_NAME="${RUNNER_NAME:-locations-runner}"
RUNNER_LABELS="${RUNNER_LABELS:-self-hosted,linux}"

# --- Configure git credential store (needed for clone and deploy.sh) ---
sudo -u runner git config --global credential.helper store
echo "https://x-access-token:${GITHUB_PAT}@github.com" > /home/runner/.git-credentials
chown runner:runner /home/runner/.git-credentials
chmod 600 /home/runner/.git-credentials

# --- Register GitHub Actions runner (if not already registered) ---
RUNNER_DIR="/runner"
if [ ! -f "$RUNNER_DIR/.runner" ]; then
    echo "Registering GitHub Actions runner..."
    REPO_PATH=$(echo "$GITHUB_REPO_URL" | sed 's|https://github.com/||')
    REG_TOKEN=$(curl -fsSL \
        -X POST \
        -H "Authorization: token ${GITHUB_PAT}" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${REPO_PATH}/actions/runners/registration-token" \
        | jq -r .token)

    if [ -z "$REG_TOKEN" ] || [ "$REG_TOKEN" = "null" ]; then
        echo "ERROR: Failed to get runner registration token" >&2
        exit 1
    fi

    cd "$RUNNER_DIR"
    sudo -u runner ./config.sh \
        --url "$GITHUB_REPO_URL" \
        --token "$REG_TOKEN" \
        --name "$RUNNER_NAME" \
        --labels "$RUNNER_LABELS" \
        --unattended \
        --replace
    echo "Runner registered."
fi

# --- Clone repo if not already present ---
if [ ! -d /app/.git ]; then
    echo "Cloning repository..."
    sudo -u runner git clone "$GITHUB_REPO_URL" /app
    cd /app/server
    sudo -u runner sudo pip install --no-cache-dir $(grep -vE 'pytest|httpx' requirements.txt)
    echo "Repository cloned and dependencies installed."
else
    echo "Repository already present at /app."
fi

# --- Write commit SHA to data volume ---
cd /app
COMMIT_SHA=$(sudo -u runner git rev-parse HEAD)
echo "$COMMIT_SHA" > /data/.commit_sha
chown runner:runner /data/.commit_sha
echo "Commit SHA: $COMMIT_SHA"

# --- Ensure runner user owns /data files ---
chown runner:runner /data
chown -f runner:runner /data/*.log /data/*.db /data/.commit_sha 2>/dev/null || true

# --- Start supervisord ---
echo "Starting supervisord..."
exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf
```

### deploy/deploy.sh

Adapt for locations:
- DB name is `locations.db`, backup as `locations-backup-{sha}.db`
- pip install filters out pytest and httpx

```bash
#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/app"
DATA_DIR="/data"
DB_PATH="$DATA_DIR/locations.db"

cd "$APP_DIR"

# --- Fetch latest and compare SHAs ---
git fetch origin main
LOCAL_SHA=$(git rev-parse HEAD)
REMOTE_SHA=$(git rev-parse origin/main)

if [ "$LOCAL_SHA" = "$REMOTE_SHA" ]; then
    echo "Already up to date ($LOCAL_SHA). Nothing to deploy."
    exit 0
fi

SHORT_SHA=$(echo "$REMOTE_SHA" | cut -c1-8)
echo "Deploying $LOCAL_SHA -> $REMOTE_SHA ($SHORT_SHA)..."

# --- Backup database ---
if [ -f "$DB_PATH" ]; then
    BACKUP_PATH="$DATA_DIR/locations-backup-${SHORT_SHA}.db"
    echo "Backing up database to $BACKUP_PATH..."
    sqlite3 "$DB_PATH" ".backup '${BACKUP_PATH}'"

    # Prune old backups (keep last 5)
    BACKUP_COUNT=$(ls -1 "$DATA_DIR"/locations-backup-*.db 2>/dev/null | wc -l)
    if [ "$BACKUP_COUNT" -gt 5 ]; then
        echo "Pruning old backups (keeping last 5)..."
        ls -1t "$DATA_DIR"/locations-backup-*.db | tail -n +6 | xargs rm -f
    fi
fi

# --- Check if requirements changed ---
OLD_REQ_HASH=$(git show HEAD:server/requirements.txt 2>/dev/null | md5sum | cut -d' ' -f1)

# --- Update code ---
git reset --hard origin/main

NEW_REQ_HASH=$(md5sum server/requirements.txt | cut -d' ' -f1)

# --- Conditional pip install ---
if [ "$OLD_REQ_HASH" != "$NEW_REQ_HASH" ]; then
    echo "requirements.txt changed, installing dependencies..."
    sudo pip install --no-cache-dir $(grep -vE 'pytest|httpx' server/requirements.txt)
else
    echo "requirements.txt unchanged, skipping pip install."
fi

# --- Write new commit SHA ---
echo "$REMOTE_SHA" > "$DATA_DIR/.commit_sha"
echo "Updated .commit_sha to $REMOTE_SHA"

# --- Restart app ---
echo "Restarting app..."
supervisorctl restart app
echo "Deploy complete."
```

### .github/workflows/build-runner.yml

```yaml
name: Build Runner Image

on:
  push:
    branches: [main]
    paths: [deploy/**]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: deploy/
          push: true
          tags: |
            ghcr.io/${{ github.repository }}-runner:latest
            ghcr.io/${{ github.repository }}-runner:${{ github.sha }}
```

### .github/workflows/deploy.yml (replace existing)

```yaml
name: Deploy

on:
  push:
    branches: [main]
    paths: [server/**]

jobs:
  deploy:
    runs-on: [self-hosted, linux]
    steps:
      - name: Deploy
        run: /deploy/deploy.sh
```

### docker-compose.yml (move from server/ to project root)

```yaml
services:
  location-tracker:
    image: ghcr.io/jhennessy/locations-runner:latest
    container_name: location-tracker
    restart: unless-stopped
    ports:
      - "8080:8380"
    volumes:
      - ./data:/data
    env_file: .env
    environment:
      - DATABASE_URL=sqlite:////data/locations.db
      - DATA_DIR=/data
      - ENV=production
```

### .env.example (replace existing at server/.env.example, move to project root)

```
# Generate with: python3 -c "import secrets; print(secrets.token_hex(32))"
SECRET_KEY=change-me
STORAGE_SECRET=change-me

# GitHub PAT with repo + admin:repo scope (for runner registration and git pull)
GITHUB_PAT=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Repository URL
GITHUB_REPO_URL=https://github.com/jhennessy/locations

# Runner name (optional, default: locations-runner)
RUNNER_NAME=locations-runner

# Runner labels (optional, default: self-hosted,linux)
RUNNER_LABELS=self-hosted,linux
```

## Files to modify

### server/pages.py (line ~298)
Change the COMMIT_SHA display to read from `/data/.commit_sha`:

```python
# Replace:
commit_sha = os.environ.get("COMMIT_SHA", "")[:8]

# With:
commit_sha = ""
sha_file = os.path.join(os.environ.get("DATA_DIR", "data"), ".commit_sha")
try:
    with open(sha_file) as f:
        commit_sha = f.read().strip()[:8]
except FileNotFoundError:
    commit_sha = os.environ.get("COMMIT_SHA", "")[:8]
```

### server/api.py — if there's a /health endpoint
Update it to read SHA from file too (same pattern as above, falling back to env var).

### server/api.py — if there's a deploy endpoint
Remove it entirely (along with DEPLOY_TOKEN/WATCHTOWER_TOKEN/WATCHTOWER_URL variables).

## Files to delete

- `server/Dockerfile` — replaced by `deploy/Dockerfile`
- `server/docker-compose.yml` — replaced by root `docker-compose.yml`
- `server/.env.example` — replaced by root `.env.example`

## Important notes

- The GHCR package `locations-runner` will default to private. Either make it public or run `docker login ghcr.io` on the NAS.
- The PAT needs `repo` scope (classic token) or `Administration: Read & Write` permission (fine-grained token) to register runners.
- `libicu-dev` is required in the Dockerfile for the GitHub Actions runner's .NET runtime.
- The pip install in entrypoint.sh and deploy.sh must filter out pytest and httpx (matching the current Dockerfile's behaviour).
- The app listens on port 8380 (set in main.py), so docker-compose maps 8080:8380.
