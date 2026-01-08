#!/bin/sh
set -eu

# Fail fast on missing required vars
req() {
  name="$1"
  eval "val=\${$name:-}"
  if [ -z "$val" ]; then
    echo "Missing required env var: $name" >&2
    exit 2
  fi
}

req inputUrl
req mapping
req postUrl
req feedUrl

# Optional vars are fine if empty
serialization="${serialization:-turtle}"
placeholder="${placeholder:-__SOURCE_URL__}"
bearer="${bearer:-}"

# Build argv (quote everything!)
set -- \
  --inputUrl "$inputUrl" \
  --mapping "$mapping" \
  --postUrl "$postUrl" \
  --feedUrl "$feedUrl" \
  --serialization "$serialization" \
  --placeholder "$placeholder"

# Optional args (only add if set)
[ -n "${title:-}" ]        && set -- "$@" --title "$title"
[ -n "${description:-}" ]  && set -- "$@" --description "$description"
[ -n "${keywords:-}" ]     && set -- "$@" --keywords "$keywords"
[ -n "${ontologies:-}" ]   && set -- "$@" --ontologies "$ontologies"
[ -n "${shapes:-}" ]       && set -- "$@" --shapes "$shapes"
[ -n "${profile:-}" ]      && set -- "$@" --profile "$profile"
[ -n "$bearer" ]           && set -- "$@" --bearer "$bearer"

echo "Launching jar with args:" >&2
printf '  %s\n' "$@" >&2

exec java ${JAVA_OPTS:-} -jar /app/target/rml-post-1.0.0.jar "$@"
