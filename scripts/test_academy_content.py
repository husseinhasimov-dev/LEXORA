"""Run with python scripts/test_academy_content.py from any directory."""
import collections
import json
import re
import sqlite3
import unittest
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
READINGS = [r for file in sorted(ASSETS.glob("academy-readings*.json")) for r in json.loads(file.read_text(encoding="utf-8"))]
STORIES = [p for p in json.loads((ASSETS/"explore-passages.json").read_text(encoding="utf-8")) if p["kind"] == "R"]
LESSONS = [line.split("|") for line in (ASSETS/"academy-grammar.tsv").read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]
TOKEN = re.compile(r"[^\W\d_]+(?:[-’'][^\W\d_]+)*")

class AcademyContent(unittest.TestCase):
    def test_ids_levels_and_length_inventory(self):
        entries = READINGS + STORIES
        self.assertEqual(len(entries), len({r["id"] for r in entries}))
        self.assertEqual({r["level"] for r in entries}, {"A1","A2","B1","B2"})
        counts = [len(TOKEN.findall(r["text"])) for r in READINGS]
        for low,high in [(150,250),(350,550),(700,1000),(1200,1800)]:
            self.assertTrue(any(low <= count <= high for count in counts), (low,high,counts))
    def test_reading_questions_have_distinct_key_and_explanation(self):
        for r in READINGS:
            self.assertGreaterEqual(len(r["questions"]),2)
            for q in r["questions"]:
                self.assertTrue(q["key"] and q["why"] and q["prompt"], r["id"])
                choices = [q["key"],*q["choices"]]
                self.assertEqual(len(choices),len(set(choices)),q["prompt"])
                if q["type"] == "True / false":
                    self.assertEqual(set(choices),{"True","False"})
                if q["type"] == "Matching":
                    pairs = [part.split(" → ") for part in q["key"].split("; ")]
                    self.assertTrue(all(len(p)==2 for p in pairs))
                    self.assertEqual(len(pairs),len({p[1] for p in pairs}))
    def test_explicit_forms_resolve_uniquely_and_occur_in_text(self):
        db=sqlite3.connect((ASSETS/"catalog.sqlite").as_uri()+"?mode=ro",uri=True)
        try:
            for r in READINGS:
                tokens=set(TOKEN.findall(r["text"]))
                for form,lemma in r["forms"].items():
                    self.assertIn(form,tokens,r["id"])
                    self.assertEqual(db.execute("select count(*) from word where lemma=?",(lemma,)).fetchone()[0],1,(r["id"],lemma))
        finally:
            db.close()
    def test_grammar_prerequisites_and_keys(self):
        seen=set()
        for row in LESSONS:
            self.assertEqual(len(row),15,row[0])
            self.assertNotIn(row[0],seen)
            self.assertTrue(not row[3] or row[3] in seen,(row[0],row[3]))
            seen.add(row[0])
            self.assertTrue(all(field.strip() for field in row[4:]),row[0])
            self.assertEqual(len(set(row[7::2])),4,row[0])
        self.assertEqual(collections.Counter(l[1] for l in LESSONS),{"A1":13,"A2":10,"B1":8,"B2":7})
    def test_grammar_annotations_quote_the_passage(self):
        ids={row[0] for row in LESSONS}
        for r in READINGS:
            self.assertIn(r["grammar"],ids)
            self.assertIn(r["quote"],r["text"],r["id"])

if __name__ == "__main__":
    unittest.main(verbosity=2)
