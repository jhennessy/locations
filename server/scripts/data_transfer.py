#!/usr/bin/env python3
"""Interactive tool to sync the data directory with a remote server.

Uses MD5 checksums to transfer only changed or missing files (like rsync over REST).

Config is stored at ~/.config/locations/transfer.json so the secret persists.
"""

import hashlib
import json
import os
import secrets
import sys
import time

import requests

DATA_DIR = os.path.realpath(os.path.join(os.path.dirname(__file__), "..", "data"))
CONFIG_DIR = os.path.expanduser("~/.config/locations")
CONFIG_FILE = os.path.join(CONFIG_DIR, "transfer.json")
DEFAULT_SERVER = "https://locationz.codelook.ch"

SKIP_FILES = {".DS_Store", "locationz.log"}

BOLD = "\033[1m"
DIM = "\033[2m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
CYAN = "\033[36m"
RESET = "\033[0m"


def load_config() -> dict:
    if os.path.isfile(CONFIG_FILE):
        with open(CONFIG_FILE) as f:
            return json.load(f)
    return {}


def save_config(config: dict):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)
    os.chmod(CONFIG_FILE, 0o600)


def format_size(nbytes: int) -> str:
    if nbytes < 1024:
        return f"{nbytes} B"
    elif nbytes < 1024 * 1024:
        return f"{nbytes / 1024:.1f} KB"
    else:
        return f"{nbytes / (1024 * 1024):.1f} MB"


def print_header():
    print()
    print(f"{BOLD}╔══════════════════════════════════════╗{RESET}")
    print(f"{BOLD}║    Locationz Data Transfer Tool      ║{RESET}")
    print(f"{BOLD}╚══════════════════════════════════════╝{RESET}")
    print()


def prompt_server(config: dict) -> str:
    saved = config.get("server", DEFAULT_SERVER)
    url = input(f"  Server URL [{saved}]: ").strip()
    if not url:
        url = saved
    return url.rstrip("/")


def prompt_secret(config: dict) -> tuple[str, bool]:
    """Return (secret, is_new). Loads saved secret or generates a new one."""
    saved = config.get("secret")
    if saved:
        masked = saved[:8] + "..." + saved[-8:]
        print(f"  Saved secret: {DIM}{masked}{RESET}")
        use_saved = input(f"  Use saved secret? [Y/n]: ").strip().lower()
        if use_saved != "n":
            return saved, False

    secret = secrets.token_hex(256)
    return secret, True


def check_status(server: str, secret: str) -> dict:
    resp = requests.get(
        f"{server}/api/data/status",
        headers={"X-Data-Secret": secret},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


# --- Checksum helpers ---


def compute_local_checksums(data_dir: str) -> dict[str, dict]:
    """Compute MD5 checksums for all local files. Returns {rel_path: {md5, size}}."""
    result = {}
    if not os.path.isdir(data_dir):
        return result
    for root, _, filenames in os.walk(data_dir):
        for name in filenames:
            if name in SKIP_FILES:
                continue
            full = os.path.join(root, name)
            rel = os.path.relpath(full, data_dir)
            md5 = hashlib.md5()
            with open(full, "rb") as f:
                for chunk in iter(lambda: f.read(8192), b""):
                    md5.update(chunk)
            result[rel] = {"md5": md5.hexdigest(), "size": os.path.getsize(full)}
    return result


def get_remote_checksums(server: str, secret: str) -> dict[str, dict]:
    resp = requests.get(
        f"{server}/api/data/checksums",
        headers={"X-Data-Secret": secret},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["files"]


def compute_diff(local: dict, remote: dict) -> tuple[list[str], list[str], list[str]]:
    """Returns (missing_locally, missing_remotely, changed) relative paths."""
    missing_locally = sorted(p for p in remote if p not in local)
    missing_remotely = sorted(p for p in local if p not in remote)
    changed = sorted(p for p in local if p in remote and local[p]["md5"] != remote[p]["md5"])
    return missing_locally, missing_remotely, changed


def print_diff_summary(local: dict, remote: dict, missing_locally: list, missing_remotely: list, changed: list):
    local_size = sum(f["size"] for f in local.values())
    remote_size = sum(f["size"] for f in remote.values())
    print(f"  Local:  {len(local)} files ({format_size(local_size)})")
    print(f"  Remote: {len(remote)} files ({format_size(remote_size)})")
    print()

    if not missing_locally and not missing_remotely and not changed:
        print(f"  {GREEN}Everything in sync!{RESET}")
        return

    if missing_locally:
        size = sum(remote[p]["size"] for p in missing_locally)
        print(f"  {CYAN}Missing locally:{RESET}  {len(missing_locally)} files ({format_size(size)})")
        for p in missing_locally:
            print(f"    + {p} ({format_size(remote[p]['size'])})")

    if missing_remotely:
        size = sum(local[p]["size"] for p in missing_remotely)
        print(f"  {CYAN}Missing remotely:{RESET} {len(missing_remotely)} files ({format_size(size)})")
        for p in missing_remotely:
            print(f"    + {p} ({format_size(local[p]['size'])})")

    if changed:
        print(f"  {YELLOW}Changed:{RESET}          {len(changed)} files")
        for p in changed:
            print(f"    ~ {p} (local {format_size(local[p]['size'])} / remote {format_size(remote[p]['size'])})")


# --- Transfer helpers ---


def upload_file(server: str, secret: str, rel_path: str, abs_path: str) -> int:
    size = os.path.getsize(abs_path)
    with open(abs_path, "rb") as f:
        resp = requests.post(
            f"{server}/api/data/upload",
            headers={"X-Data-Secret": secret, "Path": rel_path},
            files={"file": (os.path.basename(abs_path), f, "application/octet-stream")},
            timeout=120,
        )
    resp.raise_for_status()
    return size


def download_file(server: str, secret: str, rel_path: str, dest_path: str) -> int:
    resp = requests.get(
        f"{server}/api/data/download",
        headers={"X-Data-Secret": secret},
        params={"path": rel_path},
        timeout=120,
        stream=True,
    )
    resp.raise_for_status()
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    total = 0
    with open(dest_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=1024 * 1024):
            f.write(chunk)
            total += len(chunk)
    return total


def transfer_files(files: list[str], direction: str, server: str, secret: str, source: dict):
    """Transfer a list of files. direction is 'upload' or 'download'."""
    done = 0
    failed = 0
    start = time.time()

    for i, rel in enumerate(files, 1):
        size = source[rel]["size"]
        label = f"  [{i}/{len(files)}] {rel} ({format_size(size)})"
        try:
            if direction == "upload":
                upload_file(server, secret, rel, os.path.join(DATA_DIR, rel))
            else:
                download_file(server, secret, rel, os.path.join(DATA_DIR, rel))
            done += 1
            print(f"{label} {GREEN}OK{RESET}")
        except requests.exceptions.HTTPError as e:
            failed += 1
            detail = e.response.text[:100] if e.response else str(e)
            print(f"{label} {RED}FAILED: {detail}{RESET}")
        except Exception as e:
            failed += 1
            print(f"{label} {RED}FAILED: {e}{RESET}")

    elapsed = time.time() - start
    verb = "uploaded" if direction == "upload" else "downloaded"
    print(f"\n  {BOLD}Done:{RESET} {done} {verb}, {failed} failed ({elapsed:.1f}s)")


# --- Actions ---


def do_status(server: str, secret: str):
    print(f"\n  Comparing checksums...")
    local = compute_local_checksums(DATA_DIR)
    remote = get_remote_checksums(server, secret)
    missing_locally, missing_remotely, changed = compute_diff(local, remote)
    print()
    print_diff_summary(local, remote, missing_locally, missing_remotely, changed)


def do_download(server: str, secret: str):
    print(f"\n  Comparing checksums...")
    local = compute_local_checksums(DATA_DIR)
    remote = get_remote_checksums(server, secret)
    missing_locally, _, changed = compute_diff(local, remote)

    to_download = missing_locally + changed
    if not to_download:
        print(f"\n  {GREEN}Local data is up to date!{RESET}")
        return

    total_size = sum(remote[p]["size"] for p in to_download)
    print(f"\n  {BOLD}Download: {len(to_download)} files ({format_size(total_size)}){RESET}")
    if missing_locally:
        print(f"    {len(missing_locally)} new")
    if changed:
        print(f"    {len(changed)} changed")
    print(f"  To: {DATA_DIR}")
    print()

    confirm = input(f"  Proceed? [Y/n]: ").strip().lower()
    if confirm == "n":
        return

    os.makedirs(DATA_DIR, exist_ok=True)
    print()
    transfer_files(to_download, "download", server, secret, remote)


def do_upload(server: str, secret: str):
    if not os.path.isdir(DATA_DIR):
        print(f"\n  {RED}Data directory not found: {DATA_DIR}{RESET}")
        return

    print(f"\n  Comparing checksums...")
    local = compute_local_checksums(DATA_DIR)
    remote = get_remote_checksums(server, secret)
    _, missing_remotely, changed = compute_diff(local, remote)

    to_upload = missing_remotely + changed
    if not to_upload:
        print(f"\n  {GREEN}Remote data is up to date!{RESET}")
        return

    total_size = sum(local[p]["size"] for p in to_upload)
    print(f"\n  {BOLD}Upload: {len(to_upload)} files ({format_size(total_size)}){RESET}")
    if missing_remotely:
        print(f"    {len(missing_remotely)} new")
    if changed:
        print(f"    {len(changed)} changed")
    print(f"  From: {DATA_DIR}")
    print()

    confirm = input(f"  Proceed? [Y/n]: ").strip().lower()
    if confirm == "n":
        return

    print()
    transfer_files(to_upload, "upload", server, secret, local)


def main():
    print_header()
    config = load_config()

    # Server
    print(f"{BOLD}Server{RESET}")
    server = prompt_server(config)
    print()

    # Secret
    print(f"{BOLD}Secret{RESET}")
    secret, is_new = prompt_secret(config)

    if is_new:
        print()
        print(f"  Set this as {CYAN}DATA_SECRET{RESET} on your server:")
        print()
        print(f"    {YELLOW}{secret}{RESET}")
        print()
        print(f"  {DIM}Then restart/redeploy the server.{RESET}")
        print()
        input(f"  Press {BOLD}Enter{RESET} when ready... ")

    # Save config
    config["server"] = server
    config["secret"] = secret
    save_config(config)
    print()

    # Connect
    print(f"{BOLD}Connecting...{RESET}")
    try:
        status = check_status(server, secret)
        print(f"  {GREEN}Connected!{RESET} Server data dir: {status['data_dir']}")
    except requests.exceptions.ConnectionError:
        print(f"  {RED}Could not connect to {server}{RESET}")
        sys.exit(1)
    except requests.exceptions.HTTPError as e:
        print(f"  {RED}Server error: {e.response.status_code} {e.response.text[:100]}{RESET}")
        sys.exit(1)

    # Action menu
    while True:
        print()
        print(f"{BOLD}What would you like to do?{RESET}")
        print(f"  {CYAN}1{RESET} Upload changed files to server")
        print(f"  {CYAN}2{RESET} Download changed files from server")
        print(f"  {CYAN}3{RESET} Sync status (dry run)")
        print(f"  {CYAN}q{RESET} Quit")
        print()
        choice = input(f"  Choice: ").strip().lower()

        if choice == "1":
            do_upload(server, secret)
        elif choice == "2":
            do_download(server, secret)
        elif choice == "3":
            do_status(server, secret)
        elif choice in ("q", ""):
            print(f"\n  {DIM}Goodbye!{RESET}\n")
            break
        else:
            print(f"  {YELLOW}Invalid choice{RESET}")


if __name__ == "__main__":
    main()
