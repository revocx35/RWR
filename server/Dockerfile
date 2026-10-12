FROM python:3.12-slim-trixie

LABEL org.opencontainers.image.source="https://github.com/revocx35/RWR" \
      org.opencontainers.image.description="RWR onlooker detection server" \
      org.opencontainers.image.licenses="GPL-3.0-only"

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1

# MediaPipe's native library links against GLES/EGL even when running on the CPU.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgles2 libegl1 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt models.sha256 fetch_models.py ./
RUN pip install -r requirements.txt \
 && pip install --no-deps mediapipe==1.0.1 \
 && python fetch_models.py /app/models

COPY rwr_server ./rwr_server

# Unprivileged user; /data is the only writable path (compose mounts the rootfs read-only).
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin rwr \
 && mkdir -p /data && chown rwr /data
USER 10001
VOLUME /data
EXPOSE 8443

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD ["python", "-m", "rwr_server.healthcheck"]
CMD ["python", "-m", "rwr_server"]
