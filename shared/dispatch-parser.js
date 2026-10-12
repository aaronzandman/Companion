/* Companion – dispatch text reader.
   Turns texts like
     "801624020 2035 scale printer scale not advancing Sev: 3 P1593 - THE VILLAGE SHOPPING CENTER Retail Stores."
   into calls the Route Planner understands. Returns [] for texts that aren't dispatches. */
(function (root) {
  "use strict";
  var PRIO = { "1": "1 - Urgent", "2": "2 - High", "3": "3 - Standard", "4": "4 - Low" };
  // order (9-10 digits) … description … Sev: N … P#### - CENTER NAME … (optional trailer)
  var RX = /(\d{9,10})\s+([\s\S]+?)\s+Sev\s*:?\s*(\d)\s+P0*(\d{3,5})\s*[-–—]\s*([\s\S]+?)(?=\s+\d{9,10}\s|\s*$)/gi;
  function clean(s) { return String(s || "").replace(/\s+/g, " ").trim(); }
  function parse(text, receivedAt) {
    var when = receivedAt ? new Date(receivedAt) : new Date(), out = [], m;
    RX.lastIndex = 0;
    while ((m = RX.exec(String(text || ""))) !== null) {
      var center = clean(m[5]).replace(/\s*\bRetail Stores?\.?$/i, "").replace(/[.\s]+$/, "");
      out.push({
        order: m[1], description: clean(m[2]), severity: +m[3], priority: PRIO[m[3]] || (m[3] + " - Standard"),
        store: m[4], center: center, receivedAt: when.toISOString()
      });
    }
    return out;
  }
  // 15-column SAP-style row the planner already imports. Texts carry no due date, so date/time stay blank
  // (no false "past due"); status "SMS" shows where the call came from. A later SAP import fills in the due date.
  function toPlannerRow(c) {
    return ["SMS", "", "", "", c.priority, "P" + c.store, "", "", "", c.order, c.description, "", "SMS", "", c.store].join("\t");
  }
  root.DispatchParser = { parse: parse, toPlannerRow: toPlannerRow };
  if (typeof module !== "undefined") module.exports = root.DispatchParser;
})(typeof window !== "undefined" ? window : globalThis);
