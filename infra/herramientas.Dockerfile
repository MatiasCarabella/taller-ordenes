FROM docker:28-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d
RUN apk add --no-cache age=1.2.1-r10 python3=3.12.16-r0 git=2.49.1-r0 tzdata=2026f-r0 openssl=3.5.9-r0
ENV PYTHONDONTWRITEBYTECODE=1
COPY scripts/recuperacion.py /app/recuperacion.py
ENTRYPOINT ["python3", "/app/recuperacion.py"]
