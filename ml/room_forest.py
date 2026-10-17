"""Train a random forest that maps a Wi-Fi strength vector to a room.

Labelled data is either a See Through training JSON file or a CSV at
ml/data/labelled.csv:

    room,ap_0,ap_1,ap_2
    1,420,0,880
    2,10,600,0

`room` is an integer from 1 to 5. Every other column is one access point's
strength, in access-point order. The same position must be the same access
point in every row and in any vector you later ask the model to guess. A blank
CSV cell, or a 0 in the JSON, means that access point was not heard.

Identical readings (same room and same strength vector) are dropped before
training. The first copy is kept.

Train:

    python room_forest.py train --data path/to/seethrough-wifi-train.json

Guess a room from a new vector of the same length:

    python room_forest.py predict --vector 420,0,880

Training also writes a portable JSON file beside the joblib model
(ml/models/room_forest.json). The Android app copies that file into the APK.
Retrain, then rebuild the app, and the new forest replaces the old one.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np
from joblib import dump, load
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report
from sklearn.model_selection import train_test_split

ROOMS = (1, 2, 3, 4, 5)
ROOM_COLUMN = "room"
HERE = Path(__file__).resolve().parent
DEFAULT_DATA = HERE / "data" / "labelled.csv"
DEFAULT_MODEL = HERE / "models" / "room_forest.joblib"
PORTABLE_FORMAT = "seethrough-room-forest-v1"

N_ESTIMATORS = 300
RANDOM_STATE = 0
HOLDOUT_FRACTION = 0.2
MIN_ROWS_PER_ROOM_FOR_HOLDOUT = 2


class DataError(Exception):
    """The labelled file or a query vector cannot be used."""


PROTOCOL = "seethrough-wifi-train-v1"
STRENGTH_MIN = 1
STRENGTH_MAX = 1000


def load_labelled(path: Path) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Load a training JSON file or a labelled CSV."""
    if path.suffix.lower() == ".json":
        return load_labelled_json(path)
    return load_labelled_csv(path)


def load_labelled_json(path: Path) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Load a seethrough-wifi-train-v1 document. Column order follows access_points."""
    document = _read_json(path)
    protocol = document.get("protocol")
    if protocol != PROTOCOL:
        raise DataError(f"{path} uses protocol {protocol!r}; this trainer reads {PROTOCOL}.")

    access_points = document.get("access_points")
    samples = document.get("samples")
    if not isinstance(access_points, list) or not access_points:
        raise DataError(f"{path} needs an access_points list.")
    if not isinstance(samples, list) or not samples:
        raise DataError(f"{path} has no readings.")

    feature_names: list[str] = []
    for index, access_point in enumerate(access_points, start=1):
        if not isinstance(access_point, dict):
            raise DataError(f"{path} access point {index} is not an object.")
        bssid = access_point.get("bssid")
        if not isinstance(bssid, str) or not bssid.strip():
            raise DataError(f"{path} access point {index} needs a bssid.")
        feature_names.append(bssid.strip())
    if len(set(feature_names)) != len(feature_names):
        raise DataError(f"{path} repeats a BSSID. Each access point needs one column.")

    width = len(feature_names)
    strengths: list[list[float]] = []
    rooms: list[int] = []
    for index, sample in enumerate(samples, start=1):
        if not isinstance(sample, dict):
            raise DataError(f"{path} sample {index} is not an object.")
        vector = sample.get("vector")
        if not isinstance(vector, list) or len(vector) != width:
            got = len(vector) if isinstance(vector, list) else 0
            raise DataError(
                f"{path} sample {index} has {got} strengths; every vector must have {width}."
            )
        rooms.append(_json_room(sample.get("room"), path, index))
        strengths.append(
            [_json_strength(value, path, index, position) for position, value in enumerate(vector, start=1)]
        )
    return _checked_labels(path, strengths, rooms, feature_names)


def drop_duplicate_readings(
    strengths: np.ndarray,
    rooms: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, int]:
    """Keep the first copy of each room and strength vector."""
    seen: set[tuple[int, tuple[float, ...]]] = set()
    kept_strengths: list[np.ndarray] = []
    kept_rooms: list[int] = []
    for vector, room in zip(strengths, rooms):
        key = (int(room), tuple(float(value) for value in vector))
        if key in seen:
            continue
        seen.add(key)
        kept_strengths.append(vector)
        kept_rooms.append(int(room))
    if not kept_rooms:
        raise DataError("No readings left after removing duplicates.")
    removed = len(rooms) - len(kept_rooms)
    return (
        np.asarray(kept_strengths, dtype=np.float64),
        np.asarray(kept_rooms, dtype=np.int64),
        removed,
    )


def conflicting_vector_count(strengths: np.ndarray, rooms: np.ndarray) -> int:
    """How many distinct vectors are labelled with more than one room."""
    rooms_for: dict[tuple[float, ...], set[int]] = {}
    for vector, room in zip(strengths, rooms):
        rooms_for.setdefault(tuple(float(value) for value in vector), set()).add(int(room))
    return sum(1 for labels in rooms_for.values() if len(labels) > 1)


def load_labelled_csv(path: Path) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Return strength matrix, room labels, and the strength column names."""
    if not path.is_file():
        raise DataError(
            f"No labelled data at {path}. "
            "Add a CSV there: a room column (1-5), then one strength column per access point."
        )

    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.reader(handle)
        try:
            header = next(reader)
        except StopIteration:
            header = []
        rows = [row for row in reader if any(cell.strip() for cell in row)]

    feature_names = _feature_names(header)
    if not rows:
        raise DataError(
            f"{path} has no readings. Add one row per scan: room, then the strength vector."
        )

    strengths: list[list[float]] = []
    rooms: list[int] = []
    width = len(feature_names)
    for line_number, row in enumerate(rows, start=2):
        if len(row) != width + 1:
            raise DataError(
                f"{path} line {line_number} has {max(len(row) - 1, 0)} strengths; "
                f"every vector must have {width}."
            )
        rooms.append(_room(row[0], path, line_number))
        strengths.append([_strength(cell, path, line_number, name) for cell, name in zip(row[1:], feature_names)])

    return _checked_labels(path, strengths, rooms, feature_names)


def train(strengths: np.ndarray, rooms: np.ndarray) -> tuple[RandomForestClassifier, str | None]:
    """Fit a forest on every reading. Return it and a holdout report when one is possible."""
    report = _holdout_report(strengths, rooms)
    forest = _new_forest()
    forest.fit(strengths, rooms)
    return forest, report


def save_model(path: Path, forest: RandomForestClassifier, feature_names: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    dump({"forest": forest, "features": feature_names}, path)


def portable_path(model_path: Path) -> Path:
    """JSON file the Android app loads. It sits beside the joblib model."""
    return model_path.with_suffix(".json")


def checks_path(model_path: Path) -> Path:
    return model_path.with_name(f"{model_path.stem}.checks.json")


def export_bundle(forest: RandomForestClassifier, feature_names: list[str]) -> dict:
    """A forest the phone can score without scikit-learn.

    Each tree is stored as parallel arrays. A leaf has left == -1. An internal
    node sends the vector left when the strength is less than or equal to the
    threshold, matching scikit-learn. counts[node][i] is the weighted number of
    training rows for classes[i] that reached that node.
    """
    trees = []
    for estimator in forest.estimators_:
        tree = estimator.tree_
        trees.append(
            {
                "feature": [int(value) for value in tree.feature],
                "threshold": [float(value) for value in tree.threshold],
                "left": [int(value) for value in tree.children_left],
                "right": [int(value) for value in tree.children_right],
                "counts": [[float(value) for value in row] for row in tree.value[:, 0, :]],
            }
        )
    return {
        "format": PORTABLE_FORMAT,
        "note": "Retrain with ml/room_forest.py and rebuild the Android app to replace this model.",
        "features": [name.strip().lower() for name in feature_names],
        "classes": [int(label) for label in forest.classes_],
        "trees": trees,
    }


def predict_portable(bundle: dict, vector: list[float]) -> int:
    """Room chosen by the same vote the phone uses: the mean of each tree's leaf shares."""
    classes = bundle["classes"]
    if len(vector) != len(bundle["features"]):
        raise DataError(
            f"This vector has {len(vector)} strengths; the model has {len(bundle['features'])}."
        )
    scores = [0.0] * len(classes)
    for tree in bundle["trees"]:
        node = 0
        left = tree["left"]
        guard = len(left)
        hops = 0
        while left[node] != -1:
            hops += 1
            if hops > guard:
                raise DataError("A tree in the portable model never reached a leaf.")
            feature = tree["feature"][node]
            if vector[feature] <= tree["threshold"][node]:
                node = left[node]
            else:
                node = tree["right"][node]
        counts = tree["counts"][node]
        total = sum(counts)
        if total <= 0:
            continue
        for index, count in enumerate(counts):
            scores[index] += count / total
    best = max(range(len(scores)), key=scores.__getitem__)
    return int(classes[best])


def save_portable(path: Path, bundle: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(bundle, separators=(",", ":")), encoding="utf-8")


def save_checks(path: Path, bundle: dict, strengths: np.ndarray, rooms: np.ndarray) -> None:
    """A few vectors and the room the portable model gives them, for the Android test."""
    picks: list[int] = []
    seen: set[int] = set()
    for index, room in enumerate(rooms):
        label = int(room)
        if label in seen:
            continue
        seen.add(label)
        picks.append(index)
    if len(rooms) > 1 and (len(rooms) - 1) not in picks:
        picks.append(len(rooms) - 1)
    cases = [_check_case(bundle, strengths[index]) for index in picks]
    cases.append(_check_case(bundle, np.zeros(len(bundle["features"]))))
    path.write_text(
        json.dumps({"format": PORTABLE_FORMAT, "cases": cases}, indent=2) + "\n",
        encoding="utf-8",
    )


def _check_case(bundle: dict, vector: np.ndarray) -> dict:
    values = [float(value) for value in vector]
    return {
        "vector": values,
        "room": predict_portable(bundle, values),
    }


def predict_room(model_path: Path, vector: list[float]) -> tuple[int, list[tuple[int, float]]]:
    """Return the guessed room and each seen room's share of the trees' votes."""
    if not model_path.is_file():
        raise DataError(f"No trained model at {model_path}. Run train after the labelled CSV is in place.")
    bundle = load(model_path)
    forest: RandomForestClassifier = bundle["forest"]
    feature_names: list[str] = bundle["features"]
    if len(vector) != len(feature_names):
        raise DataError(
            f"This vector has {len(vector)} strengths; the model was trained on {len(feature_names)} "
            f"({', '.join(feature_names)})."
        )
    sample = np.asarray([vector], dtype=np.float64)
    room = int(forest.predict(sample)[0])
    shares = forest.predict_proba(sample)[0]
    votes = [(int(label), float(share)) for label, share in zip(forest.classes_, shares)]
    return room, votes


def parse_vector(text: str) -> list[float]:
    cells = [cell.strip() for cell in text.split(",")]
    if not cells or any(cell == "" for cell in cells):
        raise DataError("Pass the strength vector as comma-separated numbers, for example 420,0,880.")
    vector: list[float] = []
    for cell in cells:
        try:
            vector.append(float(cell))
        except ValueError:
            raise DataError(f"Strength {cell!r} is not a number.") from None
    return vector


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Train or query a room random forest on Wi-Fi strength vectors.")
    commands = parser.add_subparsers(dest="command", required=True)

    train_command = commands.add_parser("train", help="Fit a forest on labelled JSON or CSV and save it.")
    train_command.add_argument("--data", type=Path, default=DEFAULT_DATA, help=f"Training JSON or CSV (default: {DEFAULT_DATA})")
    train_command.add_argument("--model", type=Path, default=DEFAULT_MODEL, help=f"Where to write the forest (default: {DEFAULT_MODEL})")

    predict_command = commands.add_parser("predict", help="Guess a room from one strength vector.")
    predict_command.add_argument("--model", type=Path, default=DEFAULT_MODEL, help=f"Trained forest (default: {DEFAULT_MODEL})")
    predict_command.add_argument("--vector", required=True, help="Comma-separated strengths in training-column order.")

    args = parser.parse_args(argv)
    try:
        if args.command == "train":
            _run_train(args.data, args.model)
        else:
            _run_predict(args.model, args.vector)
    except DataError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


def _run_train(data_path: Path, model_path: Path) -> None:
    loaded, loaded_rooms, feature_names = load_labelled(data_path)
    raw_count = len(loaded_rooms)
    strengths, rooms, removed = drop_duplicate_readings(loaded, loaded_rooms)
    if removed:
        print(f"Removed {removed} duplicate readings from {raw_count}; {len(rooms)} unique readings remain.")
    conflicts = conflicting_vector_count(strengths, rooms)
    if conflicts:
        print(
            f"Warning: {conflicts} distinct vectors are labelled with more than one room.",
            file=sys.stderr,
        )
    forest, report = train(strengths, rooms)
    save_model(model_path, forest, feature_names)
    bundle = export_bundle(forest, feature_names)
    _require_portable_matches(forest, bundle, strengths)
    json_path = portable_path(model_path)
    save_portable(json_path, bundle)
    save_checks(checks_path(model_path), bundle, strengths, rooms)
    counts = ", ".join(f"{room}: {int(np.sum(rooms == room))}" for room in ROOMS if np.any(rooms == room))
    print(f"Trained on {len(rooms)} readings, {len(feature_names)} strengths each ({counts}).")
    if report is not None:
        print(report)
    else:
        print("Too few readings per room for a holdout check. The saved model was fit on every row.")
    print(f"Saved {model_path}")
    print(f"Saved {json_path}")


def _require_portable_matches(
    forest: RandomForestClassifier,
    bundle: dict,
    strengths: np.ndarray,
) -> None:
    """The JSON file has to choose the same room as scikit-learn on every training row."""
    expected = forest.predict(strengths)
    for index, (vector, room) in enumerate(zip(strengths, expected)):
        chosen = predict_portable(bundle, [float(value) for value in vector])
        if chosen != int(room):
            raise DataError(
                f"Portable model disagreed with scikit-learn on training row {index + 1}: "
                f"{chosen} instead of {int(room)}."
            )


def _run_predict(model_path: Path, vector_text: str) -> None:
    room, votes = predict_room(model_path, parse_vector(vector_text))
    shares = ", ".join(f"{label}: {share:.2f}" for label, share in votes)
    print(f"room {room}")
    print(f"vote share: {shares}")


def _checked_labels(
    path: Path,
    strengths: list[list[float]],
    rooms: list[int],
    feature_names: list[str],
) -> tuple[np.ndarray, np.ndarray, list[str]]:
    labels = np.asarray(rooms, dtype=np.int64)
    present = set(int(room) for room in labels)
    missing = [room for room in ROOMS if room not in present]
    if len(present) < 2:
        raise DataError(
            f"{path} only includes room {next(iter(present))}. "
            "Add readings from at least two of rooms 1-5 before training."
        )
    if missing:
        print(
            "Warning: no readings for room "
            + ", ".join(str(room) for room in missing)
            + ". The model cannot guess a room it has never seen.",
            file=sys.stderr,
        )
    return np.asarray(strengths, dtype=np.float64), labels, feature_names


def _read_json(path: Path) -> dict:
    if not path.is_file():
        raise DataError(f"No labelled data at {path}.")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise DataError(f"{path} is not valid JSON: {error}.") from None
    if not isinstance(document, dict):
        raise DataError(f"{path} must be a JSON object.")
    return document


def _json_room(value: object, path: Path, sample_index: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value not in ROOMS:
        raise DataError(f"{path} sample {sample_index}: room must be an integer from 1 to 5, got {value!r}.")
    return value


def _json_strength(value: object, path: Path, sample_index: int, position: int) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise DataError(f"{path} sample {sample_index}: strength {position} is not a number ({value!r}).")
    if isinstance(value, float) and not value.is_integer():
        raise DataError(f"{path} sample {sample_index}: strength {position} must be an integer, got {value!r}.")
    number = int(value)
    if number != 0 and not STRENGTH_MIN <= number <= STRENGTH_MAX:
        raise DataError(
            f"{path} sample {sample_index}: strength {position} must be 0 or {STRENGTH_MIN}-{STRENGTH_MAX}, got {number}."
        )
    return float(number)


def _feature_names(header: list[str]) -> list[str]:
    names = [cell.strip() for cell in header]
    if not names or names[0].lower() != ROOM_COLUMN:
        raise DataError(
            "The CSV header must start with 'room', followed by one column per access point."
        )
    features = names[1:]
    if not features:
        raise DataError("The CSV needs one strength column per access point after 'room'.")
    if any(not name for name in features):
        raise DataError("Every strength column needs a header name.")
    if len(set(features)) != len(features):
        raise DataError("Strength column names must be unique so each access point has one position.")
    return features


def _room(cell: str, path: Path, line_number: int) -> int:
    text = cell.strip()
    try:
        value = float(text)
    except ValueError:
        value = None
    if value is None or not value.is_integer() or int(value) not in ROOMS:
        raise DataError(f"{path} line {line_number}: room must be an integer from 1 to 5, got {cell.strip()!r}.")
    return int(value)


def _strength(cell: str, path: Path, line_number: int, column: str) -> float:
    text = cell.strip()
    if text == "":
        return 0.0
    try:
        return float(text)
    except ValueError:
        raise DataError(f"{path} line {line_number}: strength {column!r} is not a number ({text!r}).") from None


def _new_forest() -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=N_ESTIMATORS,
        random_state=RANDOM_STATE,
        class_weight="balanced",
        n_jobs=1,
    )


def _holdout_report(strengths: np.ndarray, rooms: np.ndarray) -> str | None:
    _, counts = np.unique(rooms, return_counts=True)
    if counts.min() < MIN_ROWS_PER_ROOM_FOR_HOLDOUT:
        return None
    if int(len(rooms) * HOLDOUT_FRACTION) < len(counts):
        return None
    train_x, test_x, train_y, test_y = train_test_split(
        strengths,
        rooms,
        test_size=HOLDOUT_FRACTION,
        random_state=RANDOM_STATE,
        stratify=rooms,
    )
    forest = _new_forest()
    forest.fit(train_x, train_y)
    guessed = forest.predict(test_x)
    accuracy = accuracy_score(test_y, guessed)
    details = classification_report(test_y, guessed, digits=2, zero_division=0)
    return f"Holdout accuracy on {len(test_y)} readings: {accuracy:.2f}\n{details}".rstrip()


if __name__ == "__main__":
    sys.exit(main())
