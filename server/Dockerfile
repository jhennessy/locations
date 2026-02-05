FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir \
    $(grep -vE 'pytest|httpx' requirements.txt)

COPY . .

ARG COMMIT_SHA=""
ENV COMMIT_SHA=${COMMIT_SHA}

EXPOSE 8080

CMD ["python", "main.py"]
