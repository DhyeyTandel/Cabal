#!/usr/bin/env bash
# Written for the bash 3.2 that ships with macOS (no negative indices; JSON built with jq).
# Seeds one office and 24 employees across Bengaluru, then plans a morning pickup,
# cancels one rider, and adds a late booking. Requires the app on :8080 and jq.
set -euo pipefail
API=${API:-http://localhost:8080/api}

post() { curl -sf -X POST "$API$1" -H 'Content-Type: application/json' ${2:+-d "$2"}; }

office=$(post /offices '{"name":"Manyata Tech Park","latitude":13.0475,"longitude":77.6206}' | jq .id)
echo "office $office"

# name gender lat lng  (approximate neighbourhood centres)
homes=(
  "Asha FEMALE 13.0358 77.5970"    # Hebbal
  "Ravi MALE 13.1007 77.5963"      # Yelahanka
  "Meera FEMALE 13.0450 77.6300"   # Nagawara
  "Kiran MALE 13.0280 77.6400"     # Kalyan Nagar
  "Divya FEMALE 13.0600 77.6300"   # Thanisandra
  "Arjun MALE 13.0350 77.6480"     # HRBR Layout
  "Neha FEMALE 12.9784 77.6408"    # Indiranagar
  "Vikram MALE 12.9698 77.7500"    # Whitefield
  "Pooja FEMALE 12.9591 77.6974"   # Marathahalli
  "Rahul MALE 13.0070 77.6950"     # KR Puram
  "Sneha FEMALE 12.9352 77.6245"   # Koramangala
  "Aditya MALE 12.9116 77.6389"    # HSR Layout
  "Kavya FEMALE 12.9166 77.6101"   # BTM Layout
  "Rohan MALE 12.9250 77.5938"     # Jayanagar
  "Isha FEMALE 13.0035 77.5709"    # Malleshwaram
  "Sanjay MALE 12.9911 77.5540"    # Rajajinagar
  "Priya FEMALE 13.0213 77.5947"   # RT Nagar
  "Manoj MALE 13.0158 77.6480"     # Banaswadi
  "Anjali FEMALE 13.0400 77.6150"  # Hebbal Kempapura
  "Suresh MALE 13.0580 77.5950"    # Sahakar Nagar
  "Lakshmi FEMALE 12.8452 77.6602" # Electronic City (far)
  "Nikhil MALE 13.0700 77.6450"    # Hennur
  "Tara FEMALE 12.9900 77.6600"    # CV Raman Nagar
  "Varun MALE 13.1300 77.6100"     # Jakkur
)
ids=()
for h in "${homes[@]}"; do
  read -r name gender lat lng <<<"$h"
  body=$(jq -nc --arg n "$name" --arg g "$gender" --argjson lat "$lat" --argjson lng "$lng" --argjson o "$office" \
    '{name: $n, gender: $g, latitude: $lat, longitude: $lng, officeId: $o}')
  ids+=("$(post /employees "$body" | jq .id)")
done
late=${ids[23]}
roster=$(printf '%s\n' "${ids[@]:0:23}" | jq -s -c .)

summary='"plan \(.id) rev \(.revision): \(.cabCount) cabs, \(.employeeCount) riders, \(.totalDistanceKm) km",
  (.cabs[] | "  cab \(.cabNumber) [\(.seatsUsed) seats, \(.distanceKm) km, longest ride \(.maxRideMinutes) min\(if .escortRequired then ", ESCORT" else "" end)]: " +
     ([.stops[] | "\(.employeeName) \(.eta[11:16])"] | join(" -> ")) + " -> office \(.officeTime[11:16])")'

echo; echo "== 21:00 night-shift pickup, 4-seat cabs =="
body=$(jq -nc --argjson o "$office" --argjson r "$roster" \
  '{officeId: $o, shiftTime: "2026-10-01T21:00:00", direction: "PICKUP", employeeIds: $r, cabCapacity: 4}')
plan=$(post /plans "$body")
echo "$plan" | jq -r "$summary"
pid=$(echo "$plan" | jq .id)

echo; echo "== ${homes[1]%% *} cancels (LOCAL repair) =="
curl -sf -X DELETE "$API/plans/$pid/employees/${ids[1]}" | jq -r "$summary"

echo; echo "== late booking: ${homes[23]%% *} =="
post "/plans/$pid/employees/$late" | jq -r "$summary"
