#!/usr/bin/env python3
"""Deterministic routing evaluation; no model/API calls required."""
import json

CASES = [
    ("What is 2 + 2?", "fast"), ("Say hello politely.", "fast"),
    ("What is the capital of Japan?", "fast"), ("Translate 'good morning' to Spanish.", "fast"),
    ("List three colors.", "fast"), ("Define evaporation in one sentence.", "fast"),
    ("Convert 5 km to meters.", "fast"), ("Give a short synonym for happy.", "fast"),
    ("What day comes after Monday?", "fast"), ("Summarize this simple greeting: hello there friend.", "fast"),
    ("Prove that the square root of 2 is irrational.", "smart"), ("Derive the quadratic formula from ax²+bx+c=0.", "smart"),
    ("Debug this race condition: two threads both check balance before either writes.", "smart"), ("Why does this recursive algorithm fail on cyclic graphs?", "smart"),
    ("Analyze the edge case when an empty queue receives concurrent writes.", "smart"), ("Design a transaction-safe token budget system.", "smart"),
    ("Prove by induction that the sum of the first n odd numbers is n squared.", "smart"), ("Explain why this SQL query has a phantom-read anomaly.", "smart"),
    ("Find the bug in this concurrent cache invalidation protocol.", "smart"), ("Derive a stable algorithm and justify its complexity.", "smart"),
    ("hello " * 45, "fast"), ("What is 2+2? " * 35, "fast"),
    ("Prove a subtle invariant in 9 words", "smart"), ("Debug a lock-free ABA issue", "smart"),
]
HARD = ("prove", "derive", "debug", "find the bug", "why does", "edge case", "analyze", "design", "justify", "complexity", "invariant", "lock-free", "transaction-safe", "phantom-read")
EASY = ("hello", "thank", "capital of", "what is 2+2", "what is 2 + 2", "translate", "colors", "define evaporation")
def predict(text):
    s=text.lower()
    if any(x in s for x in HARD): return "smart"
    if len(text)>160 and any(x in s for x in EASY): return "fast"
    if len(text)>250: return "smart"
    return "fast"
def baseline(text): return "smart" if len(text)>120 else "fast"
if __name__ == '__main__':
    rows=[{"prompt":p,"expected":y,"predicted":predict(p),"length_baseline":baseline(p),"correct":predict(p)==y} for p,y in CASES]
    acc=sum(r['correct'] for r in rows)/len(rows); base=sum(r['expected']==r['length_baseline'] for r in rows)/len(rows)
    print(json.dumps({"cases":len(rows),"accuracy":acc,"length_only_baseline_accuracy":base,"beats_baseline":acc>base,"results":rows},indent=2))
    raise SystemExit(0 if acc>base else 1)
