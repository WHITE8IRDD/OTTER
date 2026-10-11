#!/usr/bin/env bash
# Diagnostic probe: what does Instagram actually return to a CI runner?
# Writes probe_<label>.txt dumps plus probe_status.txt for the summariser.
set +e

UA='Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'
POST='https://www.instagram.com/p/DeEo7SjjLYz/'
URL='https://www.instagram.com/p/DeEo7SjjLYz/?img_index=2&mdxt=MTB3NWI3aGFiYWg3eQ=='
MEDIA_ID=4000502365245191731

: > probe_status.txt

probe () {
  local label="$1"; shift
  local out="probe_${label}.txt"
  local code
  code=$(curl -sS -L --max-time 45 \
    -H 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' \
    -H 'Accept-Language: en-US,en;q=0.9' \
    -o "$out" -w '%{http_code} %{size_download} %{content_type}' "$@" 2>&1 | tail -1)
  echo "${label}: ${code}" >> probe_status.txt
  echo "=== ${label} -> ${code}" >> probe_status.txt
}

BOT_UA='Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'
FB_UA='facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)'

probe page            "$POST" -A "$UA"
probe page_query      "$URL"
probe a1              "${POST}?__a=1&__d=dis"
probe embed           "https://www.instagram.com/p/DeEo7SjjLYz/embed/captioned/"
probe webapi          "https://www.instagram.com/api/v1/media/${MEDIA_ID}/info/" -H 'X-IG-App-ID: 936619743392459'
probe iapi            "https://i.instagram.com/api/v1/media/${MEDIA_ID}/info/" -H 'X-IG-App-ID: 936619743392459'
probe googlebot       "$POST" -A "$BOT_UA"
probe googlebot_embed "https://www.instagram.com/p/DeEo7SjjLYz/embed/captioned/" -A "$BOT_UA"
probe facebookbot     "$POST" -A "$FB_UA"
probe googlebot_webapi "https://www.instagram.com/api/v1/media/${MEDIA_ID}/info/" -A "$BOT_UA" -H 'X-IG-App-ID: 936619743392459'

echo "probe finished"
cat probe_status.txt
