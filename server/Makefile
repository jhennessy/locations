IMAGE := locationz

.PHONY: prod dev test transfer

prod:
	docker build -t $(IMAGE) .
	docker run --rm -p 8080:8080 --env-file .env -v $$(pwd)/data:/data -e DATA_DIR=/data $(IMAGE)

dev:
	uv run python main.py

test:
	uv run pytest

transfer:
	uv run python scripts/data_transfer.py
