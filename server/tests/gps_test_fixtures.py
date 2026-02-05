"""GPS test fixture data simulating a morning commute in San Francisco.

Data sources (for reference and further testing):
- Microsoft GeoLife GPS Trajectory Dataset:
  https://www.microsoft.com/en-us/download/details.aspx?id=52367
- OpenStreetMap public GPS traces:
  https://www.openstreetmap.org/traces/
- Grab-Posisi Dataset (includes accuracy/speed/bearing):
  https://engineering.grab.com/grab-posisi
- UCI GPS Trajectories:
  https://archive.ics.uci.edu/dataset/354/gps+trajectories

The fixture below contains 50 points across 5 segments:
1. HOME (7 pts, 12 min) - stationary cluster near 37.7615, -122.4240
2. WALK_TO_COFFEE (10 pts, 5 min) - moving ~595m NE
3. COFFEE_SHOP (5 pts, 8 min) - stationary near 37.7655, -122.4195
4. WALK_TO_OFFICE (16 pts, 12 min) - moving ~1096m N
5. OFFICE (12 pts, 33 min) - stationary near 37.7738, -122.4128

GPS noise is realistic: +/-5-15m scatter on stationary, indoor accuracy
degradation, phantom drift speed, altitude jitter.
"""

import datetime

# Canonical centers for expected visit detection
HOME_CENTER = {"latitude": 37.7615, "longitude": -122.4240}
COFFEE_SHOP_CENTER = {"latitude": 37.7655, "longitude": -122.4195}
OFFICE_CENTER = {"latitude": 37.7738, "longitude": -122.4128}

_BASE = datetime.datetime(2024, 1, 15, 8, 0, 0)  # 08:00 PST


def _t(minutes, seconds=0):
    return _BASE + datetime.timedelta(minutes=minutes, seconds=seconds)


# -- Segment 1: Home (7 points over 12 minutes) --
HOME_SEGMENT = [
    {"latitude": 37.76148, "longitude": -122.42405, "altitude": 22.3, "horizontal_accuracy": 8.0, "speed": 0.0, "timestamp": _t(0)},
    {"latitude": 37.76153, "longitude": -122.42398, "altitude": 21.8, "horizontal_accuracy": 10.0, "speed": 0.2, "timestamp": _t(2)},
    {"latitude": 37.76146, "longitude": -122.42410, "altitude": 23.1, "horizontal_accuracy": 7.0, "speed": 0.1, "timestamp": _t(4)},
    {"latitude": 37.76155, "longitude": -122.42393, "altitude": 22.0, "horizontal_accuracy": 12.0, "speed": 0.3, "timestamp": _t(6)},
    {"latitude": 37.76149, "longitude": -122.42402, "altitude": 22.7, "horizontal_accuracy": 9.0, "speed": 0.0, "timestamp": _t(8)},
    {"latitude": 37.76144, "longitude": -122.42408, "altitude": 21.5, "horizontal_accuracy": 11.0, "speed": 0.4, "timestamp": _t(10)},
    {"latitude": 37.76151, "longitude": -122.42400, "altitude": 22.5, "horizontal_accuracy": 8.0, "speed": 0.1, "timestamp": _t(12)},
]

# -- Segment 2: Walk to coffee shop (10 points over 5 minutes) --
WALK_TO_COFFEE = [
    {"latitude": 37.76180, "longitude": -122.42370, "altitude": 23.0, "horizontal_accuracy": 6.0, "speed": 1.3, "timestamp": _t(12, 30)},
    {"latitude": 37.76210, "longitude": -122.42340, "altitude": 23.5, "horizontal_accuracy": 7.0, "speed": 1.4, "timestamp": _t(13)},
    {"latitude": 37.76245, "longitude": -122.42310, "altitude": 24.1, "horizontal_accuracy": 6.0, "speed": 1.3, "timestamp": _t(13, 30)},
    {"latitude": 37.76280, "longitude": -122.42280, "altitude": 24.8, "horizontal_accuracy": 8.0, "speed": 1.2, "timestamp": _t(14)},
    {"latitude": 37.76320, "longitude": -122.42245, "altitude": 25.2, "horizontal_accuracy": 7.0, "speed": 1.5, "timestamp": _t(14, 30)},
    {"latitude": 37.76360, "longitude": -122.42210, "altitude": 25.0, "horizontal_accuracy": 6.0, "speed": 1.4, "timestamp": _t(15)},
    {"latitude": 37.76400, "longitude": -122.42170, "altitude": 24.7, "horizontal_accuracy": 9.0, "speed": 1.3, "timestamp": _t(15, 30)},
    {"latitude": 37.76440, "longitude": -122.42130, "altitude": 25.3, "horizontal_accuracy": 7.0, "speed": 1.4, "timestamp": _t(16)},
    {"latitude": 37.76490, "longitude": -122.42000, "altitude": 25.8, "horizontal_accuracy": 6.0, "speed": 1.5, "timestamp": _t(16, 30)},
    {"latitude": 37.76540, "longitude": -122.41960, "altitude": 26.1, "horizontal_accuracy": 8.0, "speed": 1.2, "timestamp": _t(17)},
]

# -- Segment 3: Coffee shop (5 points over 8 minutes) --
COFFEE_SEGMENT = [
    {"latitude": 37.76552, "longitude": -122.41955, "altitude": 26.5, "horizontal_accuracy": 18.0, "speed": 0.0, "timestamp": _t(17, 30)},
    {"latitude": 37.76548, "longitude": -122.41948, "altitude": 26.0, "horizontal_accuracy": 22.0, "speed": 0.2, "timestamp": _t(19, 30)},
    {"latitude": 37.76555, "longitude": -122.41952, "altitude": 27.1, "horizontal_accuracy": 25.0, "speed": 0.1, "timestamp": _t(21, 30)},
    {"latitude": 37.76545, "longitude": -122.41958, "altitude": 25.8, "horizontal_accuracy": 20.0, "speed": 0.3, "timestamp": _t(23, 30)},
    {"latitude": 37.76550, "longitude": -122.41950, "altitude": 26.3, "horizontal_accuracy": 19.0, "speed": 0.0, "timestamp": _t(25, 30)},
]

# -- Segment 4: Walk to office (16 points over 12 minutes) --
WALK_TO_OFFICE = [
    {"latitude": 37.76580, "longitude": -122.41920, "altitude": 27.0, "horizontal_accuracy": 7.0, "speed": 1.3, "timestamp": _t(26)},
    {"latitude": 37.76620, "longitude": -122.41880, "altitude": 27.5, "horizontal_accuracy": 6.0, "speed": 1.4, "timestamp": _t(26, 45)},
    {"latitude": 37.76660, "longitude": -122.41840, "altitude": 28.0, "horizontal_accuracy": 8.0, "speed": 1.2, "timestamp": _t(27, 30)},
    {"latitude": 37.76700, "longitude": -122.41800, "altitude": 28.3, "horizontal_accuracy": 7.0, "speed": 1.5, "timestamp": _t(28, 15)},
    {"latitude": 37.76750, "longitude": -122.41750, "altitude": 28.8, "horizontal_accuracy": 6.0, "speed": 1.3, "timestamp": _t(29)},
    {"latitude": 37.76800, "longitude": -122.41700, "altitude": 29.2, "horizontal_accuracy": 9.0, "speed": 1.4, "timestamp": _t(29, 45)},
    {"latitude": 37.76850, "longitude": -122.41650, "altitude": 29.5, "horizontal_accuracy": 7.0, "speed": 1.3, "timestamp": _t(30, 30)},
    {"latitude": 37.76900, "longitude": -122.41600, "altitude": 30.0, "horizontal_accuracy": 6.0, "speed": 1.5, "timestamp": _t(31, 15)},
    {"latitude": 37.76950, "longitude": -122.41550, "altitude": 30.5, "horizontal_accuracy": 8.0, "speed": 1.2, "timestamp": _t(32)},
    {"latitude": 37.77000, "longitude": -122.41500, "altitude": 30.8, "horizontal_accuracy": 7.0, "speed": 1.4, "timestamp": _t(32, 45)},
    {"latitude": 37.77060, "longitude": -122.41440, "altitude": 31.2, "horizontal_accuracy": 6.0, "speed": 1.3, "timestamp": _t(33, 30)},
    {"latitude": 37.77120, "longitude": -122.41380, "altitude": 31.5, "horizontal_accuracy": 8.0, "speed": 1.5, "timestamp": _t(34, 15)},
    {"latitude": 37.77180, "longitude": -122.41320, "altitude": 32.0, "horizontal_accuracy": 7.0, "speed": 1.4, "timestamp": _t(35)},
    {"latitude": 37.77240, "longitude": -122.41300, "altitude": 32.3, "horizontal_accuracy": 6.0, "speed": 1.3, "timestamp": _t(35, 45)},
    {"latitude": 37.77310, "longitude": -122.41290, "altitude": 32.7, "horizontal_accuracy": 9.0, "speed": 1.2, "timestamp": _t(36, 30)},
    {"latitude": 37.77370, "longitude": -122.41280, "altitude": 33.0, "horizontal_accuracy": 7.0, "speed": 1.4, "timestamp": _t(38)},
]

# -- Segment 5: Office (12 points over 33 minutes) --
OFFICE_SEGMENT = [
    {"latitude": 37.77382, "longitude": -122.41278, "altitude": 33.5, "horizontal_accuracy": 15.0, "speed": 0.0, "timestamp": _t(38, 30)},
    {"latitude": 37.77375, "longitude": -122.41285, "altitude": 33.0, "horizontal_accuracy": 20.0, "speed": 0.2, "timestamp": _t(41, 30)},
    {"latitude": 37.77388, "longitude": -122.41275, "altitude": 34.2, "horizontal_accuracy": 25.0, "speed": 0.1, "timestamp": _t(44, 30)},
    {"latitude": 37.77378, "longitude": -122.41282, "altitude": 33.8, "horizontal_accuracy": 18.0, "speed": 0.0, "timestamp": _t(47, 30)},
    {"latitude": 37.77385, "longitude": -122.41270, "altitude": 33.3, "horizontal_accuracy": 22.0, "speed": 0.3, "timestamp": _t(50, 30)},
    {"latitude": 37.77380, "longitude": -122.41280, "altitude": 34.0, "horizontal_accuracy": 16.0, "speed": 0.1, "timestamp": _t(53, 30)},
    {"latitude": 37.77390, "longitude": -122.41268, "altitude": 33.6, "horizontal_accuracy": 28.0, "speed": 0.2, "timestamp": _t(56, 30)},
    {"latitude": 37.77376, "longitude": -122.41288, "altitude": 33.2, "horizontal_accuracy": 19.0, "speed": 0.0, "timestamp": _t(59, 30)},
    {"latitude": 37.77383, "longitude": -122.41276, "altitude": 34.1, "horizontal_accuracy": 21.0, "speed": 0.1, "timestamp": _t(62, 30)},
    {"latitude": 37.77379, "longitude": -122.41283, "altitude": 33.7, "horizontal_accuracy": 17.0, "speed": 0.0, "timestamp": _t(65, 30)},
    {"latitude": 37.77386, "longitude": -122.41272, "altitude": 33.4, "horizontal_accuracy": 24.0, "speed": 0.2, "timestamp": _t(68, 30)},
    {"latitude": 37.77381, "longitude": -122.41279, "altitude": 33.9, "horizontal_accuracy": 15.0, "speed": 0.0, "timestamp": _t(71, 30)},
]

# -- Full trace --
GPS_TRACE = HOME_SEGMENT + WALK_TO_COFFEE + COFFEE_SEGMENT + WALK_TO_OFFICE + OFFICE_SEGMENT

# -- Points with errors (for filter testing) --
BAD_ACCURACY_POINT = {
    "latitude": 37.7600, "longitude": -122.4300, "altitude": 20.0,
    "horizontal_accuracy": 500.0, "speed": 0.0, "timestamp": _t(1),
}

BAD_SPEED_POINT = {
    "latitude": 37.7620, "longitude": -122.4240, "altitude": 20.0,
    "horizontal_accuracy": 5.0, "speed": 200.0, "timestamp": _t(3),
}

DUPLICATE_TIME_POINT = {
    "latitude": 37.7616, "longitude": -122.4241, "altitude": 22.0,
    "horizontal_accuracy": 8.0, "speed": 0.0, "timestamp": _t(0, 1),  # 1 sec after first
}

# -- Segment metadata for validation --
SEGMENTS = [
    {
        "name": "Home",
        "center": HOME_CENTER,
        "point_count": len(HOME_SEGMENT),
        "duration_minutes": 12,
        "is_visit": True,
    },
    {
        "name": "Walk to Coffee",
        "point_count": len(WALK_TO_COFFEE),
        "duration_minutes": 5,
        "is_visit": False,
    },
    {
        "name": "Coffee Shop",
        "center": COFFEE_SHOP_CENTER,
        "point_count": len(COFFEE_SEGMENT),
        "duration_minutes": 8,
        "is_visit": True,
    },
    {
        "name": "Walk to Office",
        "point_count": len(WALK_TO_OFFICE),
        "duration_minutes": 12,
        "is_visit": False,
    },
    {
        "name": "Office",
        "center": OFFICE_CENTER,
        "point_count": len(OFFICE_SEGMENT),
        "duration_minutes": 33,
        "is_visit": True,
    },
]
