#!/usr/bin/env python3
"""Collecte automatique de notes critiques pour une liste de vins.

Usage:
    python3 scripts/collect_ratings.py --input wines.csv --output ratings.csv
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import re
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List
from urllib.parse import quote_plus
from urllib.request import Request, urlopen

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)

RATING_PATTERNS: Dict[str, re.Pattern[str]] = {
    "robert_parker": re.compile(
        r"(?:Robert\s+Parker|Parker)[^\d]{0,25}(\d{2,3}(?:[\.,]\d+)?(?:\s*/\s*\d{2,3})?)",
        re.IGNORECASE,
    ),
    "hachette": re.compile(
        r"(?:Guide\s+Hachette|Hachette)[^\d]{0,25}(\d{1,2}(?:[\.,]\d+)?(?:\s*/\s*\d{1,2})?)",
        re.IGNORECASE,
    ),
    "wine_spectator": re.compile(
        r"(?:Wine\s+Spectator)[^\d]{0,25}(\d{2,3}(?:[\.,]\d+)?(?:\s*/\s*\d{2,3})?)",
        re.IGNORECASE,
    ),
    "jancis_robinson": re.compile(
        r"(?:Jancis\s+Robinson)[^\d]{0,25}(\d{1,2}(?:[\.,]\d+)?(?:\s*/\s*\d{2})?)",
        re.IGNORECASE,
    ),
}


@dataclass
class WineQuery:
    domaine: str
    appellation: str
    millesime: str

    def to_search_query(self) -> str:
        return f"site:millesima.fr {self.domaine} {self.appellation} {self.millesime}".strip()


def fetch_html(url: str, timeout: int) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def extract_links_from_ddg(html_doc: str, max_results: int) -> List[str]:
    # DuckDuckGo HTML SERP has links in <a ... class="result__a" ... href="...">
    pattern = re.compile(r'<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"', re.IGNORECASE)
    links: List[str] = []
    for href in pattern.findall(html_doc):
        unescaped = html.unescape(href)
        if "millesima.fr" in unescaped:
            links.append(unescaped)
        if len(links) >= max_results:
            break
    return links


def duckduckgo_links(query: str, timeout: int, max_results: int) -> List[str]:
    url = f"https://duckduckgo.com/html/?q={quote_plus(query)}"
    try:
        html_doc = fetch_html(url, timeout=timeout)
    except Exception:
        return []
    return extract_links_from_ddg(html_doc=html_doc, max_results=max_results)


def html_to_text(html_doc: str) -> str:
    cleaned = re.sub(r"<script[\\s\\S]*?</script>", " ", html_doc, flags=re.IGNORECASE)
    cleaned = re.sub(r"<style[\\s\\S]*?</style>", " ", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"<[^>]+>", " ", cleaned)
    cleaned = html.unescape(cleaned)
    return re.sub(r"\s+", " ", cleaned).strip()


def extract_ratings_from_text(text: str) -> Dict[str, str]:
    ratings: Dict[str, str] = {}
    for source, pattern in RATING_PATTERNS.items():
        match = pattern.search(text)
        if match:
            ratings[source] = match.group(1).replace(" ", "")
    return ratings


def scrape_wine_ratings(wine: WineQuery, timeout: int, max_results: int, sleep: float) -> Dict[str, str]:
    query = wine.to_search_query()
    candidates = duckduckgo_links(query=query, timeout=timeout, max_results=max_results)
    if not candidates:
        return {"best_url": "", "ratings_json": "{}", "status": "no-candidate-page"}

    for url in candidates:
        try:
            html_doc = fetch_html(url, timeout=timeout)
        except Exception:
            continue

        text = html_to_text(html_doc)
        ratings = extract_ratings_from_text(text)
        if ratings:
            return {
                "best_url": url,
                "ratings_json": json.dumps(ratings, ensure_ascii=False, separators=(",", ":")),
                "status": "ok",
            }
        time.sleep(sleep)

    return {"best_url": candidates[0], "ratings_json": "{}", "status": "no-rating-found"}


def read_wines(path: str) -> Iterable[WineQuery]:
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        required = {"domaine", "appellation", "millesime"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"Colonnes manquantes dans le CSV: {', '.join(sorted(missing))}")

        for row in reader:
            yield WineQuery(
                domaine=(row.get("domaine") or "").strip(),
                appellation=(row.get("appellation") or "").strip(),
                millesime=(row.get("millesime") or "").strip(),
            )


def write_output(path: str, rows: List[Dict[str, str]]) -> None:
    fieldnames = ["domaine", "appellation", "millesime", "best_url", "ratings_json", "status"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Collecte des notes critiques de vins")
    parser.add_argument("--input", required=True, help="CSV input: domaine,appellation,millesime")
    parser.add_argument("--output", required=True, help="CSV output enrichi")
    parser.add_argument("--max-results", type=int, default=5, help="Nombre max de pages candidates")
    parser.add_argument("--timeout", type=int, default=15, help="Timeout HTTP en secondes")
    parser.add_argument("--sleep", type=float, default=1.2, help="Pause entre requêtes")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    rows: List[Dict[str, str]] = []

    for wine in read_wines(args.input):
        result = scrape_wine_ratings(
            wine=wine,
            timeout=args.timeout,
            max_results=args.max_results,
            sleep=args.sleep,
        )
        rows.append(
            {
                "domaine": wine.domaine,
                "appellation": wine.appellation,
                "millesime": wine.millesime,
                **result,
            }
        )

    write_output(args.output, rows)


if __name__ == "__main__":
    main()
