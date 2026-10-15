// Header search. Loads search.json (written by bin/generate-site) and builds a
// MiniSearch index in the browser: one document per listing, plus tags and
// categories shown as quick-jump chips above the listing results.

// Everyday words → the tags listings actually use. Only words that don't
// already prefix-match a tag. Ambiguous words list every plausible meaning
// ("cold" → doctor, AC, fridge). Synonym matches rank below direct matches.
// Keys are compared after stemming (see `stem`).
const SYNONYMS = {
  // health
  fever: ["general physician", "doctor"],
  cough: ["general physician", "doctor"],
  cold: ["general physician", "ac repair", "appliance repair"],
  gp: ["general physician"],
  tooth: ["dentist"], teeth: ["dentist"],
  skin: ["dermatologist"],
  hair: ["dermatologist", "salon"],
  eye: ["ophthalmology"],
  bone: ["orthopaedic"], fracture: ["orthopaedic"],
  pregnancy: ["gynecology"], gynaec: ["gynecology"],
  heart: ["cardiologist"],
  blood: ["diagnostic"], scan: ["diagnostic"],
  test: ["diagnostic", "driving lessons"],
  chemist: ["pharmacy"],
  caretaker: ["nursing", "home healthcare"],
  // pets
  dog: ["vet", "pet care"], puppy: ["vet", "pet care"],
  // home
  tap: ["plumber"], leak: ["plumber"], pipe: ["plumber"],
  wiring: ["electrician"],
  fan: ["electrician", "appliance repair"],
  key: ["locksmith"], lock: ["locksmith"],
  fridge: ["appliance repair"], microwave: ["appliance repair"],
  cockroach: ["pest control"], termite: ["pest control"], rat: ["pest control"],
  maid: ["cleaning"],
  sofa: ["upholstery", "furniture"],
  // errands
  stitch: ["tailor"], blouse: ["tailor"],
  iron: ["ironing"],
  press: ["ironing", "printing"],
  xerox: ["printing"],
  garbage: ["waste management"], trash: ["waste management"],
  packer: ["moving"], mover: ["moving"], shifting: ["moving"],
  tiffin: ["homely food"],
  car: ["vehicle repair", "driving lessons"],
  bike: ["vehicle repair", "cycling"],
  scooter: ["vehicle repair"],
  shoe: ["cobbler"],
  power: ["bescom", "ups"],
};

// Light English plural stemming, applied to both documents and queries so
// "dentists" matches "dentist" and "batteries" matches "battery".
function stem(term) {
  const t = term.toLowerCase();
  if (t.length > 4 && t.endsWith("ies")) return t.slice(0, -3) + "y";
  if (t.length > 4 && /(s|x|ch|sh)es$/.test(t)) return t.slice(0, -2);
  if (t.length > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.slice(0, -1);
  return t;
}

const SEARCH_OPTIONS = {
  prefix: true,
  fuzzy: (term) => (term.length >= 5 ? 0.2 : false),
  boost: { name: 3, tagText: 2, category: 1.5 },
};

class Search {
  constructor(root, { indexUrl, maxResults = 8, maxChips = 4 }) {
    this.root = root;
    this.input = root.querySelector("[data-search-input]");
    this.results = root.querySelector("[data-search-results]");
    this.indexUrl = indexUrl;
    this.maxResults = maxResults;
    this.maxChips = maxChips;
    this.active = -1;
    this.loading = null;

    this.input.addEventListener("focus", () => { this.load(); if (this.input.value.trim()) this.search(); });
    this.input.addEventListener("input", () => this.search());
    this.input.addEventListener("keydown", (e) => this.onKeydown(e));
    document.addEventListener("click", (e) => { if (!root.contains(e.target)) this.hide(); });
    document.addEventListener("keydown", (e) => {
      if (e.key === "/" && !e.target.closest?.("input, textarea, [contenteditable]")) {
        e.preventDefault();
        this.input.focus();
      }
    });

    // Warm the index once the page is idle so search is ready before the user types.
    const warm = () => (window.requestIdleCallback || window.setTimeout)(() => this.load());
    if (document.readyState === "complete") warm(); else window.addEventListener("load", warm);
  }

  load() {
    this.loading ||= fetch(this.indexUrl)
      .then((r) => r.json())
      .then((data) => {
        this.listings = new MiniSearch({
          fields: ["name", "tagText", "category", "description", "address"],
          storeFields: ["name", "url", "category", "tags", "description", "verified"],
          extractField: (doc, field) => (field === "tagText" ? doc.tags.join(" ") : doc[field]),
          processTerm: stem,
          searchOptions: SEARCH_OPTIONS,
        });
        this.listings.addAll(data.listings);

        // Tags and categories share one index; ids are prefixed to keep them apart.
        this.chips = new MiniSearch({
          fields: ["name"],
          storeFields: ["name", "url", "count", "kind"],
          processTerm: stem,
          searchOptions: { prefix: true, fuzzy: SEARCH_OPTIONS.fuzzy },
        });
        this.chips.addAll([
          ...data.categories.map((c) => ({ ...c, id: "c:" + c.url, kind: "category" })),
          ...data.tags.map((t) => ({ ...t, id: "t:" + t.url, kind: "tag" })),
        ]);

        if (this.input.value.trim()) this.search();
      })
      .catch(() => { this.loading = null; });
    return this.loading;
  }

  // Ranking tiers: exact/prefix matches, then synonym matches (taking turns
  // between meanings so an overloaded word like "cold" shows a bit of each),
  // then typo-tolerant guesses ("fever" also fuzzy-matches "ever").
  query(index, text) {
    let hits = index.search(text, { combineWith: "AND" });
    if (!hits.length) hits = index.search(text, { combineWith: "OR" });

    const words = text.toLowerCase().split(/\s+/).map(stem);
    const direct = hits.filter((h) => h.terms.some((t) => words.some((w) => t.startsWith(w))));
    const typos = hits.filter((h) => !direct.includes(h));

    const seen = new Set(direct.map((h) => h.id));
    const synonyms = [];
    const meanings = [...new Set(words.flatMap((w) => SYNONYMS[w] || []))];
    const lists = meanings.map((m) => index.search(m, { combineWith: "AND", prefix: false, fuzzy: false }));
    for (let i = 0; lists.some((l) => i < l.length); i++) {
      for (const l of lists) {
        if (i < l.length && !seen.has(l[i].id)) { seen.add(l[i].id); synonyms.push(l[i]); }
      }
    }
    return [...direct, ...synonyms, ...typos.filter((h) => !seen.has(h.id))];
  }

  search() {
    const text = this.input.value.trim();
    this.active = -1;
    this.lastChip = null;
    if (!this.listings || text.length < 2) { this.hide(); return; }

    const listings = this.query(this.listings, text).slice(0, this.maxResults);
    const chips = this.query(this.chips, text).slice(0, this.maxChips);

    this.results.replaceChildren();
    if (chips.length) {
      const row = el("div", "search-chips");
      for (const c of chips) {
        const a = el("a", "search-result search-chip");
        a.href = c.url;
        a.setAttribute("role", "option");
        a.append(c.kind === "tag" ? "#" + c.name : c.name, " ", el("small", null, String(c.count)));
        row.append(a);
      }
      this.results.append(row);
    }

    for (const hit of listings) this.results.append(this.renderListing(hit));

    if (!listings.length && !chips.length) {
      const empty = el("div", "search-empty", "Nothing found for “" + text + "”. ");
      const contact = document.querySelector("a[data-u][data-d]");
      if (contact) {
        const ask = el("a", null, "Request a listing");
        ask.href = "mailto:" + contact.dataset.u + "@" + contact.dataset.d +
          "?subject=" + encodeURIComponent("New listing for 560005.town: " + text);
        empty.append(ask);
      }
      this.results.append(empty);
    }
    this.results.hidden = false;
  }

  renderListing(hit) {
    const a = el("a", "search-result");
    a.href = hit.url;
    a.setAttribute("role", "option");

    const title = el("strong");
    highlight(title, hit.name, hit.terms);
    const meta = el("span", "search-meta", hit.category + (hit.verified ? " · ✓ verified" : ""));
    if (hit.tags.length) meta.append(" · " + hit.tags.slice(0, 3).map((t) => "#" + t).join(" "));
    const snippet = el("span");
    highlight(snippet, excerpt(hit.description, hit.terms, 120), hit.terms);

    a.append(title, meta, snippet);
    return a;
  }

  // The chip row counts as one row: Up/Down move between rows, Left/Right
  // move between chips. Left/Right keep moving the text cursor otherwise.
  onKeydown(e) {
    const items = this.items();
    const chips = this.results.querySelectorAll(".search-chip").length;
    const onChip = this.active >= 0 && this.active < chips;
    let next = null;

    switch (e.key) {
      case "ArrowDown":
        if (onChip) next = chips < items.length ? chips : this.active;
        else next = this.active + 1;
        break;
      case "ArrowUp":
        if (this.active === chips && chips) next = this.lastChip ?? 0;
        else if (onChip) next = -1;
        else if (this.active === -1) next = items.length - 1;
        else next = this.active - 1;
        break;
      case "ArrowRight": if (onChip) next = (this.active + 1) % chips; break;
      case "ArrowLeft":  if (onChip) next = (this.active - 1 + chips) % chips; break;
      case "Enter": {
        // With nothing highlighted, Enter opens the top listing (or chip).
        const target = items[this.active] || this.results.querySelector("a.search-result:not(.search-chip)") || items[0];
        if (target) { e.preventDefault(); target.click(); }
        return;
      }
      case "Escape": this.hide(); this.input.blur(); return;
    }
    if (next === null || !items.length) return;
    e.preventDefault();
    this.setActive(next);
    if (next >= 0 && next < chips) this.lastChip = next;
  }

  // -1 clears the highlight (back to the input); past either end wraps around.
  setActive(i) {
    const items = this.items();
    items.forEach((x) => x.classList.remove("active"));
    this.active = i === -1 ? -1 : (i + items.length) % items.length;
    if (this.active < 0) return;
    items[this.active].classList.add("active");
    items[this.active].scrollIntoView({ block: "nearest" });
  }

  items() { return [...this.results.querySelectorAll(".search-result")]; }

  hide() {
    this.results.hidden = true;
    this.active = -1;
  }
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function termPattern(terms) {
  const words = terms.filter((t) => t.length > 1).map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return words.length ? new RegExp("(" + words.join("|") + ")", "gi") : null;
}

// Appends `text` to `node`, wrapping matched terms in <mark> (no innerHTML).
function highlight(node, text, terms) {
  const re = termPattern(terms);
  if (!re) { node.append(text); return; }
  let last = 0;
  for (const m of text.matchAll(re)) {
    node.append(text.slice(last, m.index), el("mark", null, m[0]));
    last = m.index + m[0].length;
  }
  node.append(text.slice(last));
}

// A window of `len` chars around the first matched term, so the match is visible.
function excerpt(text, terms, len) {
  if (text.length <= len) return text;
  const re = termPattern(terms);
  const at = re ? text.search(re) : -1;
  const start = at > len / 2 ? Math.min(at - 30, text.length - len) : 0;
  return (start > 0 ? "…" : "") + text.slice(start, start + len).trim() + "…";
}

document.querySelectorAll("[data-search]").forEach((root) => new Search(root, root.dataset));
