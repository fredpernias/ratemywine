#!/usr/bin/env python3
"""Scrape wine ratings from a website section like "Tous nos vins" using stdlib only."""

from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
import time
from dataclasses import dataclass, asdict
from html.parser import HTMLParser
from typing import Iterable
from urllib.parse import urljoin, urlparse, urldefrag
from urllib.request import Request, urlopen

RATING_WITH_SCALE_RE = re.compile(
    r"(?:(?:note|notation|rating|score)\s*[:\-]?\s*)?(\d{1,2}(?:[\.,]\d)?)\s*(/\s*(20|100)|sur\s*(20|100))",
    re.IGNORECASE,
)
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)
H1_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.IGNORECASE | re.DOTALL)
SCRIPT_JSONLD_RE = re.compile(
    r"<script[^>]*type=[\"']application/ld\+json[\"'][^>]*>(.*?)</script>",
    re.IGNORECASE | re.DOTALL,
)
TAG_RE = re.compile(r"<[^>]+>")


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.links: set[str] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "a":
            return
        for key, value in attrs:
            if key.lower() == "href" and value:
                self.links.add(value)


@dataclass
class WineRating:
    url: str
    wine_name: str
    rating_value: str
    rating_scale: str
    source: str


def strip_tags(text: str) -> str:
    return re.sub(r"\s+", " ", TAG_RE.sub(" ", text)).strip()


def normalize_url(base: str, href: str) -> str | None:
    absolute = urljoin(base, href)
    absolute, _ = urldefrag(absolute)
    parsed = urlparse(absolute)
    if parsed.scheme not in {"http", "https"}:
        return None
    return absolute.rstrip("/")


def find_title(html: str) -> str:
    title_match = TITLE_RE.search(html)
    if title_match:
        t = strip_tags(title_match.group(1))
        if t:
            return t
    h1_match = H1_RE.search(html)
    if h1_match:
        h = strip_tags(h1_match.group(1))
        if h:
            return h
    return "Titre inconnu"


def fetch_html(url: str, user_agent: str, timeout: int) -> str:
    req = Request(url, headers={"User-Agent": user_agent})
    with urlopen(req, timeout=timeout) as response:
        charset = response.headers.get_content_charset() or "utf-8"
        return response.read().decode(charset, errors="replace")


def extract_internal_links(html: str, base_url: str, domain: str) -> set[str]:
    parser = LinkParser()
    parser.feed(html)
    links: set[str] = set()
    for href in parser.links:
        normalized = normalize_url(base_url, href)
        if normalized and urlparse(normalized).netloc == domain:
            links.add(normalized)
    return links


def extract_ratings_from_json_ld(html: str, url: str, title: str) -> list[WineRating]:
    results: list[WineRating] = []
    for script_body in SCRIPT_JSONLD_RE.findall(html):
        script_body = script_body.strip()
        try:
            payload = json.loads(script_body)
        except json.JSONDecodeError:
            continue

        nodes = payload if isinstance(payload, list) else [payload]
        for node in nodes:
            if not isinstance(node, dict):
                continue
            aggregate = node.get("aggregateRating")
            if not isinstance(aggregate, dict):
                continue
            value = str(aggregate.get("ratingValue", "")).strip()
            best = str(aggregate.get("bestRating", "")).strip() or "?"
            if not value:
                continue
            name = str(node.get("name") or title)
            results.append(WineRating(url=url, wine_name=name, rating_value=value, rating_scale=best, source="json-ld"))
    return results


def extract_ratings_from_text(html: str, url: str, title: str) -> list[WineRating]:
    text = strip_tags(html)
    found: list[WineRating] = []
    seen: set[tuple[str, str]] = set()
    for value, _scale_group, s1, s2 in RATING_WITH_SCALE_RE.findall(text):
        scale = s1 or s2 or "?"
        value = value.replace(",", ".")
        key = (value, scale)
        if key in seen:
            continue
        seen.add(key)
        found.append(WineRating(url=url, wine_name=title, rating_value=value, rating_scale=scale, source="regex-text"))
    return found


def crawl_and_extract(start_url: str, max_pages: int, min_delay: float, max_delay: float, user_agent: str, timeout: int) -> list[WineRating]:
    domain = urlparse(start_url).netloc
    queue: list[str] = [start_url.rstrip("/")]
    seen_urls: set[str] = set()
    ratings: list[WineRating] = []

    while queue and len(seen_urls) < max_pages:
        url = queue.pop(0)
        if url in seen_urls:
            continue
        seen_urls.add(url)
        print(f"[{len(seen_urls)}/{max_pages}] Visite: {url}", file=sys.stderr)

        try:
            html = fetch_html(url, user_agent=user_agent, timeout=timeout)
        except Exception as exc:
            print(f"  -> Erreur HTTP: {exc}", file=sys.stderr)
            continue

        title = find_title(html)
        page_ratings = extract_ratings_from_json_ld(html, url, title) or extract_ratings_from_text(html, url, title)
        if page_ratings:
            ratings.extend(page_ratings)
            print(f"  -> {len(page_ratings)} note(s) détectée(s)", file=sys.stderr)

        for link in extract_internal_links(html, url, domain):
            if link not in seen_urls and link not in queue:
                queue.append(link)

        time.sleep(random.uniform(min_delay, max_delay))

    return ratings


def write_outputs(ratings: Iterable[WineRating], csv_path: str, json_path: str) -> None:
    rows = list(ratings)
    with open(csv_path, "w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=["url", "wine_name", "rating_value", "rating_scale", "source"])
        writer.writeheader()
        for row in rows:
            writer.writerow(asdict(row))
    with open(json_path, "w", encoding="utf-8") as json_file:
        json.dump([asdict(row) for row in rows], json_file, ensure_ascii=False, indent=2)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Crawler de notes de vins")
    parser.add_argument("start_url", help="URL de départ (ex: page 'tous nos vins')")
    parser.add_argument("--max-pages", type=int, default=200)
    parser.add_argument("--min-delay", type=float, default=1.0)
    parser.add_argument("--max-delay", type=float, default=2.0)
    parser.add_argument("--timeout", type=int, default=20)
    parser.add_argument("--csv", default="wine_ratings.csv")
    parser.add_argument("--json", default="wine_ratings.json")
    parser.add_argument("--user-agent", default="Mozilla/5.0 (compatible; WineRatingsBot/1.0)")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.min_delay > args.max_delay:
        print("Erreur: --min-delay doit être <= --max-delay", file=sys.stderr)
        return 2

    ratings = crawl_and_extract(args.start_url, args.max_pages, args.min_delay, args.max_delay, args.user_agent, args.timeout)
    write_outputs(ratings, args.csv, args.json)

    print(f"Terminé. {len(ratings)} notes extraites.")
    print(f"CSV : {args.csv}")
    print(f"JSON: {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
