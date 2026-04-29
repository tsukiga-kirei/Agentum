FROM python:3.12-slim
WORKDIR /app
COPY workers/ai-worker/pyproject.toml ./
COPY workers/ai-worker/src ./src
CMD ["python", "-m", "agentum_ai_worker"]

