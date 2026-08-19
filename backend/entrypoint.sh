#!/bin/sh
# Startup for the backend container.
#
# Order matters: the deploy checks run FIRST and abort the boot if the
# production configuration is unsafe (missing encryption key, placeholder
# SECRET_KEY, wildcard ALLOWED_HOSTS). A container that refuses to start is
# far better than one that quietly encrypts students' Hevy keys with a
# guessable key.
set -e

echo "Running deploy checks..."
python manage.py check --deploy --fail-level ERROR

echo "Applying migrations..."
python manage.py migrate --noinput

echo "Ensuring cache table..."
python manage.py createcachetable

echo "Collecting static files..."
python manage.py collectstatic --noinput

echo "Starting gunicorn..."
exec gunicorn config.wsgi:application \
    --bind 0.0.0.0:8000 \
    --workers "${GUNICORN_WORKERS:-3}" \
    --timeout 60 \
    --access-logfile - \
    --error-logfile -
