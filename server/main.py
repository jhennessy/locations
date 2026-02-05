"""Entry point: starts the NiceGUI server with the REST API mounted."""

import logging
import logging.handlers
import os

from nicegui import app, ui

from api import router
from database import init_db

# ---------------------------------------------------------------------------
# Logging setup
# ---------------------------------------------------------------------------
LOG_DIR = os.environ.get("LOG_DIR", "/data" if os.path.isdir("/data") else ".")
LOG_FILE = os.path.join(LOG_DIR, "location-tracker.log")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[
        logging.StreamHandler(),
        logging.handlers.RotatingFileHandler(
            LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3,
        ),
    ],
)
logger = logging.getLogger("locationtracker")

# Quiet noisy libraries
logging.getLogger("watchfiles").setLevel(logging.WARNING)
logging.getLogger("multipart").setLevel(logging.WARNING)

# Mount FastAPI REST endpoints for the iOS app
app.include_router(router)

# Initialize the database tables on startup
app.on_startup(init_db)

# Import pages so their @ui.page decorators register routes
import pages  # noqa: F401, E402

ui.run(
    title="Location Tracker",
    port=8080,
    storage_secret=os.environ.get("STORAGE_SECRET", "change-me-in-production"),
    show=False,
)
