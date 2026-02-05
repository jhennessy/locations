"""Entry point: starts the NiceGUI server with the REST API mounted."""

import os

from nicegui import app, ui

from api import router
from database import init_db

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
