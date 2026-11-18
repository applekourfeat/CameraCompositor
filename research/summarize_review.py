"""Read actual exported human judgments; refuse to infer missing votes or labels."""
import argparse
import json
from collections import Counter
from pathlib import Path
from evaluate import OUT


def summarize(answers, key):
    if answers.get("manifestHash") != key.get("manifestHash"):
        raise ValueError("Answers and trials use different photo sets")
    result = {}
    for model in ["nima", "gaic"]:
        trials = [t for t in key["trials"] if t["model"] == model and t["split"] == "evaluation" and t["type"] == "original"]
        counts, keeps, missing = Counter(), [], 0
        for trial in trials:
            vote = answers.get("answers", {}).get(trial["id"], {}).get("answer")
            label = answers.get("startingViews", {}).get(trial["sceneId"], {}).get("answer")
            if vote not in {"left", "right", "tie", "unsure"} or label not in {"good", "needs_work"}:
                missing += 1
                continue
            if label == "good":
                keeps.append(trial["cameraChoice"] == 0)
            else:
                counts["total"] += 1
                counts["wins"] += vote == trial["candidateSide"]
                counts["ties"] += vote == "tie"
                counts["unsure"] += vote == "unsure"
        rate = counts["wins"]/counts["total"] if counts["total"] else None
        complete = len(trials) == 60 and missing == 0
        result[model] = {"evaluationTrials": len(trials), "missingVotesOrLabels": missing,
            "improvableScenes": counts["total"], "wins": counts["wins"], "ties": counts["ties"],
            "unsure": counts["unsure"], "preferenceRate": rate,
            "alreadyGoodKeepRate": sum(keeps)/len(keeps) if keeps else None,
            "70PercentPreferenceGate": "pending" if not complete or counts["total"] < 20 else ("pass" if rate >= .7 else "fail"),
            "oldPhoneHeuristicComparison": "pending matched physical captures; static fixed crop control is not equivalent",
            "phonePerformance": "pending physical measurement", "releaseApproved": False}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("answers", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(json.loads(args.answers.read_text(encoding="utf-8")),
                              json.loads((OUT/"review-key.json").read_text(encoding="utf-8"))), indent=2))


if __name__ == "__main__":
    main()
