"use strict";

(function () {

  const $ = s => document.querySelector(s);
  const P = window.PUBLISHERS;
  let entries = [];
  let days = 7;

  const COLORS = ["#d97757", "#d9c07c", "#a78bd0", "#8ab4f8", "#c98fb0",
                  "#5fb0ae", "#d0a05f", "#7f9ede"];

  function esc(v) {
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function pct(n, total) {
    return total ? Math.round((n / total) * 100) : 0;
  }

  // -------------------------------------------------------------------------
  function compute() {
    const since = days ? Date.now() - days * 86400000 : 0;
    const list = entries.filter(e => e.at >= since);

    const byHost = new Map();
    const byOwner = new Map();
    let total = 0;
    let attached = 0;

    list.forEach(e => {
      const visits = e.visits || 1;
      total += visits;

      byHost.set(e.host, (byHost.get(e.host) || 0) + visits);

      let owner = null;
      try { owner = P.ownerOf(e.host); } catch (err) { }
      if (owner) {
        attached += visits;
        const o = byOwner.get(owner) || { count: 0, hosts: new Set() };
        o.count += visits;
        o.hosts.add(e.host);
        byOwner.set(owner, o);
      }
    });

    const owners = Array.from(byOwner.entries())
      .map(([name, o]) => ({ name, count: o.count, hosts: Array.from(o.hosts) }))
      .sort((a, b) => b.count - a.count);

    const hosts = Array.from(byHost.entries())
      .map(([host, count]) => ({ host, count, owner: P.ownerOf(host) }))
      .sort((a, b) => b.count - a.count);

    return { total, attached, owners, hosts, pages: list.length };
  }

  // -------------------------------------------------------------------------
  function summary(r) {
    if (!r.total) {
      return '<div class="card"><b>Pas encore de donnees</b>' +
        "<p>L'historique ne contient aucune page sur cette periode. Il doit etre " +
        "actif pour que ce bilan ait de la matiere.</p></div>";
    }

    const share = pct(r.attached, r.total);
    const first = r.owners[0];
    const firstShare = first ? pct(first.count, r.total) : 0;

    let verdict;
    if (!first) {
      verdict = "Aucune de vos lectures ne provient d'un groupe repertorie dans la " +
        "carte de propriete.";
    } else if (firstShare >= 30) {
      verdict = "Un seul groupe, <b>" + esc(first.name) + "</b>, represente <b>" +
        firstShare + " %</b> de vos consultations" +
        (first.hosts.length > 1
          ? " a travers " + first.hosts.length + " sites differents" : "") + ".";
    } else {
      verdict = "Vos lectures se repartissent sans qu'un groupe domine : le premier, " +
        "<b>" + esc(first.name) + "</b>, pese " + firstShare + " %.";
    }

    return '<div class="card"><b>' + r.pages + " page(s), " + r.total +
      " consultation(s)</b>" +
      "<p>" + share + " % proviennent de sites dont le proprietaire est identifie. " +
      verdict + "</p></div>";
  }

  function bars(items, total, labelKey, subKey) {
    return items.map((it, i) => {
      const n = it.count;
      const p = pct(n, total);
      const color = COLORS[i % COLORS.length];
      return `
        <div class="row">
          <div class="lb">
            <span>${esc(it[labelKey])}</span>
            <span>${p} % &middot; ${n}</span>
          </div>
          <div class="bar"><i style="width:${Math.max(p, 2)}%;background:${color}"></i></div>
          ${subKey && it[subKey] ? '<div class="lb" style="margin-top:3px">' +
            '<span style="color:var(--dim);font-size:11px">' +
            esc(Array.isArray(it[subKey]) ? it[subKey].join(", ") : it[subKey]) +
            "</span></div>" : ""}
        </div>`;
    }).join("");
  }

  function render() {
    const r = compute();
    let html = summary(r);

    if (r.owners.length) {
      html += "<h3>Par groupe proprietaire</h3>" +
        bars(r.owners.slice(0, 10), r.total, "name", "hosts");
    }

    if (r.hosts.length) {
      html += "<h3>Sites les plus consultes</h3>" +
        bars(r.hosts.slice(0, 12).map(h => ({
          count: h.count,
          host: h.host,
          sub: h.owner || "proprietaire non repertorie"
        })), r.total, "host", "sub");
    }

    if (r.total) {
      const free = r.total - r.attached;
      html += "<h3>Repartition</h3>" +
        '<div class="row"><div class="lb">' +
        '<span class="grp">Rattachees a un groupe</span><span>' +
        pct(r.attached, r.total) + " %</span></div>" +
        '<div class="bar"><i style="width:' + Math.max(pct(r.attached, r.total), 2) +
        '%;background:#d97757"></i></div></div>' +
        '<div class="row"><div class="lb">' +
        '<span class="indep">Non rattachees</span><span>' +
        pct(free, r.total) + " %</span></div>" +
        '<div class="bar"><i style="width:' + Math.max(pct(free, r.total), 2) +
        '%;background:#6fae5f"></i></div></div>';
    }

    $("#out").innerHTML = html;
  }

  // -------------------------------------------------------------------------
  document.querySelectorAll(".tools button").forEach(b => {
    b.onclick = () => {
      document.querySelectorAll(".tools button").forEach(x => x.classList.remove("on"));
      b.classList.add("on");
      days = parseInt(b.dataset.d, 10);
      render();
    };
  });

  (async function load() {
    try {
      const r = await browser.runtime.sendMessage({ type: "histList" });
      entries = (r && r.history) || [];
    } catch (e) {
      $("#msg").textContent = "Extension non joignable.";
      return;
    }
    render();
  })();
})();
