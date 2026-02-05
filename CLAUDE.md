# CLAUDE.md

## Project Overview

Personal location tracking system: iOS app (SwiftUI) + Python server (NiceGUI/FastAPI) + SQLite.

## Repository Structure

```
server/          Python backend (NiceGUI + FastAPI + SQLAlchemy)
ios/             iOS app (SwiftUI, Xcode project)
.github/         CI/CD (GitHub Actions → GHCR → Watchtower)
```

## Key Server Files

- `main.py` — Entry point, logging setup, NiceGUI startup
- `api.py` — REST API endpoints (FastAPI router at `/api`)
- `pages.py` — NiceGUI web pages (dashboard, admin, map, visits, etc.)
- `models.py` — SQLAlchemy models: User, Device, Location, Place, Visit, Config, ReprocessingJob
- `processing.py` — GPS pipeline: error filtering → visit detection → place snapping → geocoding
- `database.py` — DB init, migrations, seeding (admin user + config defaults)
- `auth.py` — HMAC-SHA256 tokens, PBKDF2 password hashing

## Build & Test Commands

```bash
# Run server tests
cd server && pytest

# Run server locally
cd server && python main.py

# Build iOS app
cd ios/LocationTracker && xcodebuild -scheme LocationTracker -destination 'generic/platform=iOS' build
```

## Development Notes

- Server runs on port 8080
- Default admin credentials: `admin` / `admin`
- SQLite database at `./data/locations.db` (Docker) or `./locations.db` (local)
- Algorithm thresholds are stored in the `config` DB table and editable via admin UI
- The processing pipeline in `processing.py` accepts a `thresholds` dict parameter; defaults come from the Config table
- All pages follow the pattern: `get_session_user()` → auth check → `_header(user)` → `_nav_drawer(user)` → content → `db.close()`
- iOS app targets iOS 26.0, uses CoreLocation background updates
- `LocationService.shared` is the singleton managing GPS tracking and upload buffering
- `APIService.shared` handles all REST communication; base URL persisted in UserDefaults
- Tabs in iOS ContentView use `LazyView` wrapper to defer MapKit initialization

## Environment Variables

Required in `server/.env`: `SECRET_KEY`, `STORAGE_SECRET`, `DEPLOY_TOKEN`, `WATCHTOWER_TOKEN`

## Deployment

Push to `main` with `server/**` changes triggers GitHub Actions → builds Docker image → pushes to GHCR → calls `/api/deploy` → Watchtower pulls new image and restarts container.
