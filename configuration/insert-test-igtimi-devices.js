// Insert two test Igitimi devices into the IGTIMI_DEVICES collection.
// Usage: mongo winddb insert-test-igtimi-devices.js
// Serial numbers match real EXPEDITION wind data in WIND_TRACKS for "505 Worlds 2014".

db.IGTIMI_DEVICES.replaceOne(
    { IGTIMI_DEVICES_ID: NumberLong(900001) },
    {
        IGTIMI_DEVICES_ID:             NumberLong(900001),
        IGTIMI_DEVICES_SERIAL_NUMBER:  "DC-FE-AAFC",
        IGTIMI_DEVICES_NAME:           "Test Wind Device 1 (DC-FE-AAFC)",
        IGTIMI_DEVICES_LAST_HEARTBEAT_MILLIS: null,
        IGTIMI_DEVICES_REMOTE_ADDRESS: null
    },
    { upsert: true }
);

db.IGTIMI_DEVICES.replaceOne(
    { IGTIMI_DEVICES_ID: NumberLong(900002) },
    {
        IGTIMI_DEVICES_ID:             NumberLong(900002),
        IGTIMI_DEVICES_SERIAL_NUMBER:  "DC-FE-AAFA",
        IGTIMI_DEVICES_NAME:           "Test Wind Device 2 (DC-FE-AAFA)",
        IGTIMI_DEVICES_LAST_HEARTBEAT_MILLIS: null,
        IGTIMI_DEVICES_REMOTE_ADDRESS: null
    },
    { upsert: true }
);

print("Done. Run with: mongo winddb insert-test-igtimi-devices.js");
