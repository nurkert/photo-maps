"use strict";

// Searches every heading of the wiki (search-index.js, written by scripts/build_site.py).
const input = document.getElementById("wiki-search");
const results = document.getElementById("wiki-results");

function escape(text) {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

input.addEventListener("input", () => {
  const query = input.value.trim().toLowerCase();
  if (query.length < 2) {
    results.hidden = true;
    return;
  }
  const words = query.split(/\s+/);
  const hits = (window.WIKI_INDEX || [])
    .filter((entry) => words.every((word) => `${entry.h} ${entry.t}`.toLowerCase().includes(word)))
    .slice(0, 12);
  results.innerHTML = hits.length
    ? hits
        .map((hit) => `<li><a href="${hit.p}.html#${hit.a}">${escape(hit.h)}<small>${escape(hit.t)}</small></a></li>`)
        .join("")
    : "<li class=\"none\">Nothing found</li>";
  results.hidden = false;
});

input.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    const first = results.querySelector("a");
    if (first) {
      location.href = first.href;
    }
  } else if (event.key === "Escape") {
    input.value = "";
    results.hidden = true;
  }
});

// "/" focuses the search, like on GitHub
document.addEventListener("keydown", (event) => {
  if (event.key === "/" && document.activeElement !== input) {
    event.preventDefault();
    input.focus();
  }
});
