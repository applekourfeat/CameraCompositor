import unittest

from review_live_planner import bounds, summarize


class PlannerReviewTests(unittest.TestCase):
    def setUp(self):
        self.key = {"manifestHash": "test", "trials": []}
        self.answers = {"manifestHash": "test", "startingViews": {}, "answers": {}}
        for i in range(60):
            scene = str(i)
            self.answers["startingViews"][scene] = {"answer": "needs_work" if i < 20 else "good"}
            for kind in ("original", "previous", "thirds"):
                trial = f"{scene}/{kind}"
                side = "right" if i % 2 else "left"
                self.key["trials"].append(dict(id=trial, sceneId=scene, type=kind,
                                               kept=i >= 20, identical=i == 19, candidateSide=side))
                if i != 19:
                    vote = side if i < 14 else ("left" if side == "right" else "right")
                    self.answers["answers"][trial] = {"answer": vote}

    def test_ties_and_losses_stay_in_denominator(self):
        results = summarize(self.answers, self.key)
        original = results["original"]
        self.assertEqual(original["counts"]["wins"], 14)
        self.assertEqual(original["counts"]["losses"], 5)
        self.assertEqual(original["counts"]["ties"], 1)
        self.assertEqual(original["counts"]["alreadyGoodKept"], 40)
        self.assertEqual(original["winFraction"], .7)
        self.assertEqual(original["preferenceGate"], "pass")
        self.assertTrue(results["thirds"]["beatsThirds"])
        self.answers["answers"]["0/original"] = {"answer": "unsure"}
        self.assertEqual(summarize(self.answers, self.key)["original"]["preferenceGate"], "fail")

    def test_incomplete_reviews_stay_pending(self):
        del self.answers["answers"]["0/original"]
        self.assertEqual(summarize(self.answers, self.key)["original"]["preferenceGate"], "pending")
        self.answers["startingViews"]["0"] = {"answer": "unsure"}
        self.assertIsNone(summarize(self.answers, self.key)["thirds"]["beatsThirds"])

    def test_rejects_answers_from_another_review(self):
        self.answers["manifestHash"] = "other"
        with self.assertRaises(ValueError):
            summarize(self.answers, self.key)

    def test_pixel_equivalent_crops(self):
        self.assertEqual(bounds((0, 0, .99999, 1), (100, 80)), (0, 0, 100, 80))
        with self.assertRaises(ValueError):
            bounds((.01, 0, .012, .8), (100, 80))
