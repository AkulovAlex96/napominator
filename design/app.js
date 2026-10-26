// Напоминатор — наполнение макетов (тема, навигация, таблицы, график, календарь).
(function () {
  const ic = (n, c) => `<svg class="ic ${c || ''}"><use href="#i-${n}"/></svg>`;

  /* ---------- Тема ---------- */
  const root = document.documentElement;
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  let mode = 'light';
  function applyTheme() {
    root.dataset.theme = mode === 'system' ? (mq.matches ? 'dark' : 'light') : mode;
    document.querySelectorAll('[data-theme-set]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.themeSet === mode)));
  }
  document.querySelectorAll('[data-theme-set]').forEach(b => b.addEventListener('click', () => { mode = b.dataset.themeSet; applyTheme(); }));
  mq.addEventListener('change', applyTheme);
  applyTheme();

  /* ---------- Нижняя навигация ---------- */
  const tabs = [
    ['today', 'today', 'Сегодня'], ['week', 'table', 'Неделя'], ['cal', 'calendar', 'Календарь'],
    ['chart', 'chart', 'Графики'], ['more', 'more', 'Ещё'],
  ];
  document.querySelectorAll('[data-nav]').forEach(nav => {
    nav.innerHTML = tabs.map(([k, i, t]) =>
      `<button class="tab" ${nav.dataset.nav === k ? 'aria-current="page"' : ''}><span class="pill">${ic(i)}</span>${t}</button>`).join('');
  });

  /* ---------- Таблица недели ---------- */
  const dow = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  const TODAY = 3; // четверг
  const rows = [
    { n: 'Зарядка', s: '2 из 3 · 67%', c: ['d', 'd', 'm', 'p', 'n', 'n', 'n'] },
    { n: 'Таблетки ×3', s: '5 из 9 · 56%', c: ['d', 'd', '2/3', '1/3', 'n', 'n', 'n'] },
    { n: 'Вода, мл', s: 'в среднем 1750', c: ['v1750', 'v2000', 'v1500', 'v750', 'n', 'n', 'n'] },
    { n: 'Вес, кг', s: 'среднее 82,1', c: ['v82.4', 'v82.1', 'm', 'v81.9', 'n', 'n', 'n'] },
    { n: 'Чтение', s: '2 из 3 · 67%', c: ['d', 's', 'd', 'p', 'n', 'n', 'n'] },
    { n: 'Давление', s: 'верхнее, среднее 127', c: ['v126', 'v128', 'm', 'p', 'n', 'n', 'n'] },
  ];
  function cell(c) {
    if (c === 'd') return `<span class="cell done" aria-label="выполнено">${ic('check')}</span>`;
    if (c === 'm') return `<span class="cell miss" aria-label="пропущено">${ic('minus')}</span>`;
    if (c === 's') return `<span class="cell shift" aria-label="перенесено">${ic('arrow')}</span>`;
    if (c === 'p') return `<span class="cell pend" aria-label="ожидает"></span>`;
    if (c === 'n') return `<span class="cell none" aria-label="нет срабатывания">·</span>`;
    if (c[0] === 'v') return `<span class="cell val">${c.slice(1).replace('.', ',')}</span>`;
    return `<span class="cell part">${c}</span>`;
  }
  const wt = document.getElementById('week-table');
  if (wt) {
    const head = `<tr><th class="name">Октябрь</th>${dow.map((d, i) => `<th class="${i === TODAY ? 'today' : ''}">${d}<small>${19 + i}</small></th>`).join('')}</tr>`;
    const body = rows.map(r => `<tr><td class="name"><b>${r.n}</b><span>${r.s}</span></td>${r.c.map((c, i) => `<td class="${i === TODAY ? 'today' : ''}">${cell(c)}</td>`).join('')}</tr>`).join('');
    wt.innerHTML = head + body;
  }
  const lg = document.getElementById('legend');
  if (lg) lg.innerHTML = [['d', 'выполнено'], ['m', 'пропущено'], ['s', 'перенесено'], ['2/3', 'частично'], ['p', 'ждёт']]
    .map(([c, t]) => `<span>${cell(c)}${t}</span>`).join('');

  /* ---------- Месяц (heatmap) ---------- */
  let seed = 7;
  const rnd = () => (seed = (seed * 48271) % 2147483647) / 2147483647;
  const heat = document.getElementById('heat');
  if (heat) {
    const habits = [['Зарядка', 'серия 9', .85], ['Таблетки ×3', '', .75], ['Вода', 'цель 2 л', .7], ['Вес', 'замер', .9], ['Чтение', '', .6], ['Давление', '', .8]];
    let html = `<div class="scale"><span></span><div>${Array.from({ length: 31 }, (_, i) => ((i + 1) % 5 === 0 || i === 0 ? i + 1 : '')).map(v => `<span>${v}</span>`).join('')}</div></div>`;
    html += habits.map(([n, s, p]) => {
      const cells = Array.from({ length: 31 }, (_, i) => {
        const d = i + 1;
        if (d > 22) return '<i></i>';
        if (d === 22) return '<i class="t"></i>';
        const r = rnd();
        return r < p ? '<i class="d"></i>' : r < p + .06 ? '<i class="p"></i>' : r < p + .1 ? '<i class="s"></i>' : '<i class="m"></i>';
      }).join('');
      return `<div class="hrow"><div class="hname">${n}<span>${s}</span></div><div class="days">${cells}</div></div>`;
    }).join('');
    heat.innerHTML = html;
  }

  /* ---------- Календарь ---------- */
  const cal = document.getElementById('cal');
  if (cal) {
    let h = dow.map(d => `<div class="dow">${d}</div>`).join('');
    const offset = 3; // 1 октября 2026 — четверг
    for (let i = 0; i < offset; i++) h += `<button class="day dim" tabindex="-1"><span>${28 + i}</span><span class="dots"></span></button>`;
    for (let d = 1; d <= 31; d++) {
      const past = d < 22, sel = d === 22;
      const n = 1 + (d * 7) % 3;
      let dots = '';
      for (let k = 0; k < n; k++) dots += `<i class="${past ? ((d + k) % 7 === 0 ? 'm' : '') : (sel ? '' : 'f')}"></i>`;
      h += `<button class="day ${sel ? 'sel now' : ''}" ${sel ? 'aria-current="date"' : ''}><span>${d}</span><span class="dots">${dots}</span></button>`;
    }
    for (let d = 1; d <= 1; d++) h += `<button class="day dim" tabindex="-1"><span>${d}</span><span class="dots"></span></button>`;
    cal.innerHTML = `<div class="grid">${h}</div>`;
  }

  /* ---------- График веса ---------- */
  const w = [82.6, 82.5, 82.8, 82.4, 82.3, 82.5, 82.9, 83.0, 82.7, 82.4, 82.2, 82.4, 82.1, 81.9, 82.0, 82.3, 82.1, 81.8, 81.6, 81.9, 82.0, 81.7, 81.5, 81.6, 81.4, 81.8, 82.0, 81.7, 82.1, 81.9];
  function line(vals, W, H, pad, lo, hi) {
    const x = i => pad.l + (W - pad.l - pad.r) * i / (vals.length - 1);
    const y = v => pad.t + (H - pad.t - pad.b) * (1 - (v - lo) / (hi - lo));
    return { x, y, path: vals.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(' ') };
  }
  const chart = document.getElementById('chart');
  if (chart) {
    const W = 330, H = 190, pad = { l: 34, r: 8, t: 8, b: 22 }, lo = 79.5, hi = 83.5;
    const L = line(w, W, H, pad, lo, hi);
    const grid = [80, 81, 82, 83].map(v => `<line class="grid" x1="${pad.l}" x2="${W - pad.r}" y1="${L.y(v)}" y2="${L.y(v)}"/><text x="0" y="${L.y(v) + 4}">${v}</text>`).join('');
    const last = w.length - 1;
    chart.innerHTML = `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="График веса за 30 дней: от 82,6 до 81,9 кг">
      ${grid}
      <line class="goal" x1="${pad.l}" x2="${W - pad.r}" y1="${L.y(80)}" y2="${L.y(80)}"/>
      <path class="area" d="${L.path} L${L.x(last)} ${H - pad.b} L${L.x(0)} ${H - pad.b}Z"/>
      <path class="line" d="${L.path}"/>
      <circle class="pt" cx="${L.x(last)}" cy="${L.y(w[last])}" r="5"/>
      <text x="${pad.l}" y="${H - 4}">23 сен</text><text x="${W / 2 - 14}" y="${H - 4}">7 окт</text><text x="${W - 36}" y="${H - 4}">22 окт</text>
    </svg>`;
  }
  const wc = document.getElementById('wchart');
  if (wc) {
    const W = 300, H = 56, v = w.slice(-14);
    const L = line(v, W, H, { l: 4, r: 4, t: 6, b: 6 }, 81.2, 82.4);
    wc.innerHTML = `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Вес за 14 дней"><path class="line" style="stroke-width:2.5" d="${L.path}"/><circle class="pt" cx="${L.x(v.length - 1)}" cy="${L.y(v[v.length - 1])}" r="4"/></svg>`;
  }
})();
