# Location Tracker

A personal location tracking system with an iOS app for continuous GPS recording and a Python web server for data processing, visit detection, and analytics.

Hosted at **https://locations.codelook.ch**

## Architecture

```
iOS App (SwiftUI)  ──REST API──▶  Python Server (NiceGUI + FastAPI)
                                        │
                                   SQLite DB
                                        │
                              ┌─────────┼─────────┐
                              │         │         │
                           Visits    Places    Geocoding
                          detection  snapping  (Nominatim)
```

- **iOS app** — Tracks location in the background, buffers GPS points, uploads in batches
- **Server** — Receives location data, runs visit detection pipeline, serves admin dashboard
- **Database** — SQLite with SQLAlchemy ORM, stored at `./data/locations.db`

## Features

- Continuous background location tracking (iOS)
- Batched uploads with retry on failure
- GPS error filtering (accuracy, speed, deduplication)
- Automatic visit detection (stationary clusters >= 5 min)
- Place snapping and deduplication
- Reverse geocoding via OpenStreetMap Nominatim
- Web dashboard with map, visits, and frequent places
- Admin panel with configurable algorithm thresholds and data regeneration
- User authentication (HMAC-SHA256 tokens, PBKDF2 password hashing)
- Admin/normal user roles
- Server log viewer (admin)
- Auto-deploy via GitHub Actions + Watchtower

## Server Setup

### Requirements

- Python 3.12+
- Dependencies: `nicegui`, `sqlalchemy`, `requests`

### Quick Start (Local)

```bash
cd server
pip install -r requirements.txt
cp .env.example .env
# Edit .env with your secrets
python main.py
```

The server starts on http://localhost:8080. Default admin login: `admin` / `admin`.

### Docker Deployment

```bash
cd server
cp .env.example .env
# Edit .env with generated secrets
mkdir -p data
docker compose up -d
```

### Environment Variables

| Variable | Purpose |
|----------|---------|
| `SECRET_KEY` | HMAC token signing key |
| `STORAGE_SECRET` | NiceGUI session encryption |
| `DEPLOY_TOKEN` | Authorize deploy endpoint from GitHub Actions |
| `WATCHTOWER_TOKEN` | Watchtower HTTP API authentication |
| `DATABASE_URL` | SQLite path (default: `sqlite:///locations.db`) |
| `LOG_DIR` | Log file directory (default: `/data` or `.`) |

Generate secrets with: `python3 -c "import secrets; print(secrets.token_hex(32))"`

## iOS App

### Requirements

- Xcode 26+
- iOS 26.0 deployment target

### Setup

1. Open `ios/LocationTracker/LocationTracker.xcodeproj` in Xcode
2. The server URL defaults to `https://locations.codelook.ch` and can be changed in Settings
3. Build and run on a device (location tracking requires a real device)

### Permissions

The app requests "Always" location access for background tracking. iOS uses a two-step flow:
1. First prompt: "Allow While Using App"
2. Second prompt (appears later): "Always Allow"

You can also enable it manually: Settings > Location Tracker > Location > Always.

## CI/CD

Pushing to `main` with changes in `server/` triggers:
1. Docker image build and push to `ghcr.io/jhennessy/locations`
2. Deploy endpoint call to trigger Watchtower container update

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/register` | No | Create account |
| POST | `/api/login` | No | Authenticate |
| GET | `/api/devices` | Yes | List devices |
| POST | `/api/devices` | Yes | Register device |
| DELETE | `/api/devices/{id}` | Yes | Delete device |
| POST | `/api/locations` | Yes | Upload location batch |
| GET | `/api/locations/{device_id}` | Yes | Get locations |
| GET | `/api/visits/{device_id}` | Yes | Get visits |
| POST | `/api/visits/{device_id}/reprocess` | Yes | Reprocess visits |
| GET | `/api/places` | Yes | Get places |
| GET | `/api/places/frequent` | Yes | Get frequent places |
| PUT | `/api/places/{id}/name` | Yes | Rename place |
| POST | `/api/change-password` | Yes | Change password |
| GET | `/api/admin/users` | Admin | List users |
| PUT | `/api/admin/users/{id}` | Admin | Update user |
| DELETE | `/api/admin/users/{id}` | Admin | Delete user |
| POST | `/api/deploy` | Token | Trigger Watchtower update |

## Testing

```bash
cd server
pip install -r requirements.txt
pytest
```

## License

Private project.
