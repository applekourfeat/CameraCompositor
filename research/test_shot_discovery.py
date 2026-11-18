"""Policy integrity checks. These cannot establish photographic quality."""
import unittest
from owner_city_experiment import expanded_boxes
from semantic_shots import choose_ideas, shortlist, overlap


def row(index, subject, gaic=3, margin=.1):
    return {"index":index,"box":expanded_boxes()[index],"subject":subject,
            "gaic":gaic,"negativeMargin":margin,"similarity":.3}


class ShotDiscoveryTests(unittest.TestCase):
    def test_candidate_geometry_supports_distinct_details_inside_observed_frame(self):
        boxes=expanded_boxes()
        self.assertEqual(295,len(boxes))
        self.assertEqual(295,len({tuple(b) for b in boxes}))
        for l,t,r,b in boxes:
            self.assertTrue(0 <= l < r <= 1 and 0 <= t < b <= 1)
            self.assertAlmostEqual(r-l,b-t)
            self.assertLessEqual(1/(r-l),3.20001)

    def test_high_full_view_score_does_not_erase_distinct_shot_ideas(self):
        rows=[row(0,"a tree",gaic=5),row(99,"a tree",gaic=3.5),row(147,"a bridge")]
        ideas=choose_ideas(rows)
        self.assertEqual([99,147],[r["index"] for r in ideas])
        self.assertEqual([99,147],[r["index"] for r in choose_ideas(list(reversed(rows)))])

    def test_rejects_empty_scene_evidence_and_nonfinite_or_invalid_results(self):
        bad=[row(99,"a tree",margin=-.1),row(105,"a tree",gaic=float("nan")),row(147,"a bridge",margin=float("inf"))]
        malformed=row(120,"a tree"); malformed["box"]=[-.1,0,.5,.5]; bad.append(malformed)
        self.assertEqual([],choose_ideas(bad))

    def test_suggestions_are_spatially_and_semantically_distinct(self):
        rows=[row(i,"same subject" if i%3 else "other subject",gaic=3+(i%11)/20) for i in range(99,197)]
        ideas=choose_ideas(rows)
        self.assertLessEqual(len(ideas),3)
        self.assertEqual(len(ideas),len({r["subject"] for r in ideas}))
        for i,a in enumerate(ideas):
            for b in ideas[i+1:]: self.assertLess(overlap(a["box"],b["box"]),.6)

    def test_shortlist_includes_each_zoom_band_when_wide_scores_dominate(self):
        boxes=expanded_boxes(); scores=list(reversed(range(len(boxes))))
        selected=shortlist(scores,boxes)
        self.assertEqual(0,selected[0])
        for band in range(6): self.assertTrue(any(1+49*band <= i < 1+49*(band+1) for i in selected))


if __name__ == "__main__": unittest.main()
