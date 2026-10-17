# SeeThrough

SeeThrough shows which room you are in on a floorplan. The phone listens to nearby Wi-Fi, and a random forest turns that strength vector into a room. Your marker sits in that room and moves when the prediction changes.

## Layout

- `app/` is the Android app. The floorplan drawing is `app/src/main/res/drawable-nodpi/floorplan.png`, and the room areas are in `FloorplanLayout.kt`.
- `ml/` trains the forest.

## Rooms

The numbers on the drawing are not the model's labels.

| Floorplan | Model |
| --- | --- |
| 1 | 1 |
| 3 | 2 |
| 4 | 3 |
| 5 | 4 |

Floorplan room 2 is not in the model. A model label of 5 is not floorplan room 2, so the marker is left off the drawing and the list says Unknown. That mapping lives in `app/src/main/java/com/seethrough/raw/RoomMap.kt`.

A scan that includes none of the model's access points is not scored, and the room stays Unknown.

## Replace the model

Train on a See Through training JSON file (or a CSV with a `room` column and one strength column per access point):

```powershell
python ml/room_forest.py train --data path\to\seethrough-wifi-train.json
```

That writes `ml/models/room_forest.json`. Rebuild the Android app. The build copies that file into the APK, so replacing the file and rebuilding replaces the model. The file lists each access point in vector order. The phone fills in the strength it hears for each one, and uses 0 when that access point is missing.

The same command also writes `ml/models/room_forest.joblib` for `python ml/room_forest.py predict`.
