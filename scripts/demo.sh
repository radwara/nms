#!/usr/bin/env bash
# Greenfield demo: drives a running service through its HTTP API and checks every result.
# Start the app in the dev profile first (simulated callbacks and demo credentials on); see README "Run the demo".
#
#   NMS_BASE_URL   default http://localhost:8080
#   NMS_API_KEY    default nms_demo1_dev-only (dev-only demo key, ADR-0001)
#   DEMO_TIMEOUT   seconds to wait for each notification to reach a final status, default 30
#
# Exits non-zero on the first unexpected HTTP status or delivery outcome.
set -euo pipefail

BASE_URL="${NMS_BASE_URL:-http://localhost:8080}"
API_KEY="${NMS_API_KEY:-nms_demo1_dev-only}"
TIMEOUT="${DEMO_TIMEOUT:-30}"
API_PATH="/api/v1/notifications"
RUN_ID="demo-$(date +%s)-$RANDOM"

STATUS="" # HTTP status of the last request
BODY=""   # response body of the last request

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

for tool in curl jq; do
  command -v "$tool" >/dev/null || fail "$tool is required"
done

# request METHOD PATH [curl args...]: sets STATUS and BODY.
request() {
  local method=$1 path=$2 out
  shift 2
  out=$(curl -sS -X "$method" -w $'\n%{http_code}' "$@" "$BASE_URL$path") ||
    fail "cannot reach $BASE_URL$path"
  STATUS=${out##*$'\n'}
  BODY=${out%$'\n'*}
}

expect_status() {
  [[ "$STATUS" == "$1" ]] || fail "expected HTTP $1, got $STATUS: $BODY"
}

# check JQ_EXPR MESSAGE: fails unless the last body satisfies the jq expression.
check() {
  jq -e "$1" <<<"$BODY" >/dev/null || fail "$2: $BODY"
}


step() {
  echo
  echo "== $1"
}

# recipient USER_ID [CHANNELS_JSON]: one recipients[] entry, e.g. recipient user-ok '["EMAIL"]'.
recipient() {
  if [[ $# -gt 1 ]]; then
    jq -cn --arg user "$1" --argjson channels "$2" '{userId: $user, channels: $channels}'
  else
    jq -cn --arg user "$1" '{userId: $user}'
  fi
}

# payload EVENT_ID RECIPIENT_JSON...: a CRITICAL fraud alert; each step uses its own eventId.
payload() {
  local event=$1
  shift
  jq -cn --arg event "$event" --argjson recipients "$(printf '%s\n' "$@" | jq -cs .)" '{
    eventId: $event, type: "FRAUD_ALERT", severity: "CRITICAL", priority: "HIGH",
    recipients: $recipients,
    subject: "Suspicious login", body: "We noticed a login from a new device."
  }'
}

# submit KEY PAYLOAD_JSON: sets STATUS and BODY; prints one line per notification.
submit() {
  request POST "$API_PATH" -H "X-API-Key: $API_KEY" -H "Idempotency-Key: $1" \
    -H "Content-Type: application/json" --data "$2"
  echo "   POST $API_PATH  key=$1  -> $STATUS"
  jq -r '.notifications[]? | "        \(.recipientId) \(.status) \(.notificationId)"' <<<"$BODY"
}

# submit_new STEP USER_ID: one recipient, key and eventId named after the step; expects 202;
# sets NOTIFICATION_ID.
submit_new() {
  submit "$RUN_ID-$1" "$(payload "$RUN_ID-$1" "$(recipient "$2")")"
  expect_status 202
  NOTIFICATION_ID=$(jq -r '.notifications[0].notificationId' <<<"$BODY")
}

# id_of USER_ID: notificationId of that recipient in the last submit response.
id_of() {
  jq -r --arg user "$1" '.notifications[] | select(.recipientId == $user) | .notificationId' <<<"$BODY"
}

# await_final ID EXPECTED: polls status until a final overallStatus or the timeout.
await_final() {
  local id=$1 expected=$2 overall="" deadline=$((SECONDS + TIMEOUT))
  while :; do
    request GET "$API_PATH/$id" -H "X-API-Key: $API_KEY"
    expect_status 200
    overall=$(jq -r '.overallStatus' <<<"$BODY")
    case "$overall" in
      DELIVERED | PARTIALLY_DELIVERED | FAILED | EXPIRED | NOT_ROUTED) break ;;
    esac
    ((SECONDS < deadline)) || fail "$id still $overall after ${TIMEOUT}s"
    sleep 0.2 # poll interval
  done
  echo "   GET  $API_PATH/$id  -> $STATUS  $(jq -r '"\(.recipientId)  overallStatus=\(.overallStatus)  requestedChannels=\(.requestedChannels // "none" | tostring)  duplicateSubmissions=\(.duplicateSubmissions)"' <<<"$BODY")"
  jq -r '.deliveries[] | "        \(.channel)  \(.status)  attempts=\(.attempts)\(if .reason then "  reason=" + .reason else "" end)"' <<<"$BODY"
  [[ "$overall" == "$expected" ]] || fail "expected overallStatus $expected, got $overall"
}

# show_audit ID: event sequence, then each routing decision with its reason.
show_audit() {
  request GET "$API_PATH/$1/audit" -H "X-API-Key: $API_KEY"
  expect_status 200
  echo "   GET  $API_PATH/$1/audit  -> $STATUS"
  jq -r '.events | map(.type + (if .channel then "(" + .channel + ")" else "" end)) | join(" ")' <<<"$BODY" |
    fold -s -w 96 | sed 's/^/        /'
  jq -r '.events[] | select(.type == "ROUTED" or .type == "ROUTE_REMOVED")
    | "        routing: \(.type) \(.channel): \(.detail)"' <<<"$BODY"
}

ok() {
  echo "   OK"
}

echo "Demo against $BASE_URL (run $RUN_ID)"

step "1. Health"
request GET /actuator/health
echo "   GET  /actuator/health  -> $STATUS  $(jq -r '.status' <<<"$BODY")"
expect_status 200
check '.status == "UP"' "service not UP"
ok

step "2. Happy path: user-ok, email + SMS, accepted, then confirmed by vendor callback"
submit_new happy user-ok
HAPPY_ID=$NOTIFICATION_ID
await_final "$HAPPY_ID" DELIVERED
check '[.deliveries[] | select(.status == "DELIVERED" and .attempts == 1)] | length == 2' \
  "expected EMAIL and SMS delivered on the first attempt"
show_audit "$HAPPY_ID"
check '[.events[] | select(.type == "SENT")] | length == 2' "expected SENT per channel before the callback"
ok

step "3. Routing: CRITICAL adds SMS; an opt-out withholds it (opt-out wins until #15)"
submit "$RUN_ID-routing" "$(payload "$RUN_ID-routing" "$(recipient user-ok '["EMAIL"]')" "$(recipient user-sms-optout)")"
expect_status 202
check '[.notifications[].notificationId] | unique | length == 2' "expected one notification per recipient"
REQUESTED_ID=$(id_of user-ok)
OPTOUT_ID=$(id_of user-sms-optout)
await_final "$REQUESTED_ID" DELIVERED
check '.requestedChannels == ["EMAIL"] and ([.deliveries[].channel] == ["EMAIL", "SMS"])' \
  "expected requested EMAIL plus escalated SMS"
show_audit "$REQUESTED_ID"
check '[.events[] | select(.type == "ROUTED") | "\(.channel) \(.detail)"] == ["EMAIL REQUESTED", "SMS SEVERITY_ESCALATION"]' \
  "expected EMAIL REQUESTED and SMS SEVERITY_ESCALATION"
await_final "$OPTOUT_ID" DELIVERED
check '[.deliveries[].channel] == ["EMAIL"]' "expected EMAIL only for the opted-out recipient"
show_audit "$OPTOUT_ID"
check '[.events[] | select(.type == "ROUTE_REMOVED" and .channel == "SMS") | .detail] == ["OPTED_OUT", "SEVERITY_ESCALATION withheld: OPTED_OUT"]' \
  "expected the SMS opt-out and the withheld escalation"
ok

step "4. Retry path: user-flaky2, two transient failures (503), then accepted and delivered"
submit_new retry user-flaky2
FLAKY_ID=$NOTIFICATION_ID
await_final "$FLAKY_ID" DELIVERED
check '[.deliveries[] | select(.status == "DELIVERED" and .attempts == 3)] | length == 2' \
  "expected both channels delivered after 3 attempts"
show_audit "$FLAKY_ID"
check '[.events[] | select(.type == "RETRY_SCHEDULED")] | length == 4' "expected two retries per channel"
ok

step "5. Duplicate submission: user-email-only, same Idempotency-Key and body twice"
DUP_KEY="$RUN_ID-duplicate"
DUP_PAYLOAD=$(payload "$RUN_ID-duplicate" "$(recipient user-email-only)")
submit "$DUP_KEY" "$DUP_PAYLOAD"
expect_status 202
DUP_ID=$(id_of user-email-only)
submit "$DUP_KEY" "$DUP_PAYLOAD"
expect_status 200
[[ "$(id_of user-email-only)" == "$DUP_ID" ]] || fail "replay returned a different notification: $BODY"
await_final "$DUP_ID" DELIVERED
check '.duplicateSubmissions == 1' "expected duplicateSubmissions 1"
ok

step "6. Bounce: user-bounce, email accepted, then bounced by vendor callback"
submit_new bounce user-bounce
BOUNCE_ID=$NOTIFICATION_ID
await_final "$BOUNCE_ID" FAILED
check '(.deliveries | length) == 1 and .deliveries[0].channel == "EMAIL"
  and .deliveries[0].status == "FAILED" and .deliveries[0].reason == "BOUNCED"' \
  "expected the email delivery FAILED with reason BOUNCED"
show_audit "$BOUNCE_ID"
ok

step "7. Validation error: submit without Idempotency-Key"
request POST "$API_PATH" -H "X-API-Key: $API_KEY" -H "Content-Type: application/json" \
  --data "$(payload "$RUN_ID-validation" "$(recipient user-ok)")"
echo "   POST $API_PATH  (no Idempotency-Key)  -> $STATUS  $(jq -c '.errors' <<<"$BODY")"
expect_status 400
check '.errors | length == 1 and .[0].code == 4001 and .[0].field == "Idempotency-Key"' "expected 4001"
ok

step "8. Authentication error: status read without X-API-Key"
request GET "$API_PATH/$HAPPY_ID"
echo "   GET  $API_PATH/$HAPPY_ID  (no X-API-Key)  -> $STATUS  $(jq -c '.errors' <<<"$BODY")"
expect_status 401
check '.errors[0].code == 4011' "expected 4011"
ok

echo
echo "Demo passed: 8 steps."
