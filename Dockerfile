# The COREPACK phone gateway as a public server. Stdlib only, so the image is just Python plus this folder.
FROM python:3.12-slim
RUN useradd -m -u 1000 app
WORKDIR /app
COPY corepack ./corepack
USER app
ENV PORT=7860 COREPACK_HOME=/tmp/corepack PYTHONUNBUFFERED=1
EXPOSE 7860
CMD ["python", "-m", "corepack", "gateway", "serve"]
