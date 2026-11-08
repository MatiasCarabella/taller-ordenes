FROM docker:28-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d
RUN apk add --no-cache age python3 git tzdata openssl
ENV PYTHONDONTWRITEBYTECODE=1
COPY scripts/recuperacion.py /app/recuperacion.py
ENTRYPOINT ["python3", "/app/recuperacion.py"]
