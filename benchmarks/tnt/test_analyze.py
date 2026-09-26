import unittest
import xml.etree.ElementTree as ET

import analyze


class AnalysisTest(unittest.TestCase):
    def test_constant(self):
        result = analyze.time_series([10.0] * 15)
        self.assertEqual(result["mean"], 10)
        self.assertEqual(result["sample_cv_percent"], 0)
        self.assertEqual(result["last5_vs_first5_percent"], 0)
        self.assertIsNone(result["lag1_pearson"])

    def test_drift(self):
        result = analyze.time_series(list(range(1, 16)))
        self.assertEqual(result["first5_mean"], 3)
        self.assertEqual(result["last5_mean"], 13)
        self.assertAlmostEqual(result["last5_vs_first5_percent"], 1000 / 3)
        self.assertAlmostEqual(result["lag1_pearson"], 1)
        self.assertEqual(analyze.tick_distribution(list(range(1, 101)))["p95"], 95)

    def test_correlation_and_zero_denominators(self):
        self.assertAlmostEqual(analyze.pearson([1, 2, 3], [6, 4, 2]), -1)
        self.assertIsNone(analyze.pearson([0, 0, 0], [1, 2, 3]))
        self.assertIsNone(analyze.pearson([], []))
        result = analyze.time_series([0] * 15)
        self.assertIsNone(result["sample_cv_percent"])
        self.assertIsNone(result["last5_vs_first5_percent"])
        with self.assertRaises(ValueError):
            analyze.pearson([1], [1, 2])

    def test_counts_refusal(self):
        rows = [{"index": i, "ticks": 200, "phase": "warmup" if i == 0 else "measure"}
                for i in range(16)]
        ticks = [None] * 3200
        analyze.validate_counts(rows, ticks)
        for windows, raw in ((rows[:-1], ticks), (rows, ticks[:-1]), (rows, ticks[200:])):
            with self.subTest(windows=len(windows), raw=len(raw)), self.assertRaises(ValueError):
                analyze.validate_counts(windows, raw)
        rows[1]["phase"] = "warmup"
        with self.assertRaises(ValueError):
            analyze.validate_counts(rows, ticks)

    def test_svg_panels_and_constant_series(self):
        rows = [{"run": f"{run:02d}", "index": i + 1,
                 "measured_mid_minutes": i + 0.5, "measured_end_minutes": i + 1,
                 "mean_ms": 10, "p95_ms": 10, "explosions_per_tick": 1,
                 "tnt_entities": 20, "item_entities": 0}
                for run in range(1, 4) for i in range(15)]
        text = analyze.timeline(rows)
        self.assertEqual(text, analyze.timeline(rows))
        root = ET.fromstring(text)
        ns = {"svg": "http://www.w3.org/2000/svg"}
        self.assertEqual(len(root.findall(".//svg:polyline", ns)), 15)
        self.assertEqual(len(root.findall(".//svg:circle", ns)), 225)
        self.assertIn("200 warmup ticks excluded", text)


if __name__ == "__main__":
    unittest.main()
