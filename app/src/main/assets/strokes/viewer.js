/* Local-only Hanzi Writer viewer. Vocabulary is inserted as text, never HTML. */
(function () {
  'use strict';
  const el = id => document.getElementById(id);
  const cache = new Map();
  let initialized = false, characters = [], selected = 0, slow = false;
  let writer = null, generation = 0, ready = false, step = 0, total = 0;
  let running = false, paused = false, busy = false, cleared = false;
  let pendingSteps = 0;
  let showGuides = false, medians = [], activeGuide = null;
  function controls() {
    el('play').disabled = !ready || (busy && !running && !paused);
    el('next').disabled = !ready;
    el('next').textContent = pendingSteps ? 'Дальше…' : step >= total ? 'К первой черте' : 'Следующая черта';
    el('replay').disabled = !ready;
    el('play').textContent = paused ? '▶ Продолжить' : running ? 'Ⅱ Пауза' : step === total && total ? '↻ Повторить' : '▶ Смотреть';
    el('slow').setAttribute('aria-pressed', String(slow));
    el('show-guides').disabled = !ready;
    el('show-guides').setAttribute('aria-pressed', String(showGuides));
    el('guide-hint').hidden = !ready || !showGuides;
  }
  function remember() {
    history.replaceState(null, '', '#i=' + selected + '&slow=' + (slow ? '1' : '0') + '&guides=' + (showGuides ? '1' : '0'));
  }
  function dimensions() { return Math.max(100, el('stage').clientWidth); }
  function svgNode(name, attributes, parent) {
    const node = document.createElementNS('http://www.w3.org/2000/svg', name);
    Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, String(value)));
    parent.append(node);
    return node;
  }
  function drawGuides() {
    const svg = el('guides');
    svg.replaceChildren();
    if (!ready || !showGuides) return;
    const size = dimensions();
    svg.setAttribute('viewBox', '0 0 ' + size + ' ' + size);
    // Use the same public transform as the character renderer, including its
    // inverted Y axis. Work in screen pixels so numbers stay readable on phones.
    const transform = HanziWriter.getScalingTransform(size, size, 18);
    const labels = [], positions = [];
    medians.forEach((median, index) => {
      if (activeGuide !== null && index !== activeGuide) return;
      const points = median.map(([x, y]) => [transform.x + x * transform.scale,
        size - transform.y - y * transform.scale]);
      const group = svgNode('g', {'data-stroke': index + 1}, svg);
      const path = points.map(([x, y], i) => (i ? 'L' : 'M') + x + ' ' + y).join(' ');
      svgNode('path', {d: path, class: 'guide-halo'}, group);
      svgNode('path', {d: path, class: 'guide-line'}, group);
      // The final distinct segment preserves the direction of short hooks;
      // using the overall start-to-end vector would point those the wrong way.
      const end = points[points.length - 1];
      const previous = points.slice(0, -1).reverse().find(p => Math.hypot(end[0] - p[0], end[1] - p[1]) > .01);
      if (previous) {
        const angle = Math.atan2(end[1] - previous[1], end[0] - previous[0]);
        svgNode('path', {class: 'guide-arrow', d: 'M0 0 L-8 -4 L-8 4 Z',
          transform: 'translate(' + end.join(' ') + ') rotate(' + angle * 180 / Math.PI + ')'}, group);
      }
      const start = points[0], next = points.find(p => Math.hypot(p[0] - start[0], p[1] - start[1]) > .01) || start;
      const angle = Math.atan2(next[1] - start[1], next[0] - start[0]);
      const radius = index < 9 ? 10 : 12;
      // Prefer just before the stroke start. Search nearby if starts overlap,
      // and connect displaced badges back to their exact starting point.
      let best = null;
      for (const distance of [16, 28, 40, 52, 64, 76, 88]) {
        for (const offset of [0, .6, -.6, 1.2, -1.2, 1.8, -1.8, Math.PI]) {
          const x = Math.max(radius + 2, Math.min(size - radius - 2, start[0] - Math.cos(angle + offset) * distance));
          const y = Math.max(radius + 2, Math.min(size - radius - 2, start[1] - Math.sin(angle + offset) * distance));
          const overlap = positions.reduce((sum, p) => sum + Math.max(0, radius + p.radius + 3 - Math.hypot(x - p.x, y - p.y)), 0);
          const score = overlap * 100 + Math.hypot(x - start[0], y - start[1]) + Math.abs(offset) * 3;
          if (!best || score < best.score) best = {x, y, radius, score};
        }
      }
      positions.push(best);
      labels.push({index, start, ...best});
    });
    labels.forEach(({start, x, y}) => {
      svgNode('path', {class: 'guide-halo', d: 'M' + start.join(' ') + ' L' + x + ' ' + y}, svg);
      svgNode('path', {class: 'guide-line', d: 'M' + start.join(' ') + ' L' + x + ' ' + y}, svg);
    });
    // Keep every number above both stroke paths and other labels' connectors.
    labels.forEach(({index, x, y, radius}) => {
      const group = svgNode('g', {class: 'guide-number', 'data-stroke': index + 1}, svg);
      svgNode('circle', {cx: x, cy: y, r: radius}, group);
      svgNode('text', {x, y}, group).textContent = index + 1;
    });
  }
  function progress() { el('status').textContent = step ? 'Черта ' + step + ' из ' + total : 'Всего черт: ' + total; }
  function unavailable(message) {
    ready = false;
    drawGuides();
    el('target').className = 'fallback';
    el('target').textContent = characters[selected] ? characters[selected].char : '';
    el('unavailable').hidden = false;
    el('unavailable').textContent = message;
    el('status').textContent = 'Анимация недоступна';
    controls();
  }
  async function select(index, autoplay, firstStroke = false) {
    const token = ++generation;
    if (writer) writer.hideCharacter({duration: 0});
    writer = null; ready = running = paused = busy = cleared = false; step = total = 0;
    pendingSteps = 0;
    medians = []; activeGuide = null; drawGuides();
    selected = index;
    remember(); controls();
    el('target').replaceChildren(); el('target').className = '';
    el('unavailable').hidden = true;
    el('status').textContent = 'Загрузка…';
    document.querySelectorAll('.chip').forEach((chip, i) => chip.setAttribute('aria-pressed', String(i === selected)));
    const entry = characters[selected];
    if (!entry) { el('position').textContent = ''; unavailable('В этом слове нет знаков для просмотра.'); return; }
    el('position').textContent = entry.char + ' · знак ' + entry.position + ' из ' + entry.count;
    el('swipe-hint').hidden = entry.count < 2;
    el('target').setAttribute('aria-label', 'Порядок черт: ' + entry.char);
    try {
      let data = cache.get(entry.char);
      if (!data) {
        const response = await fetch('data/' + entry.char.codePointAt(0).toString(16) + '.json');
        if (token !== generation) return;
        if (!response.ok) { unavailable('Для этого знака пока нет данных о порядке черт.'); return; }
        data = await response.json();
        if (token !== generation) return;
        cache.set(entry.char, data);
      }
      if (!Array.isArray(data.strokes) || !data.strokes.length) throw new Error('Missing strokes');
      medians = data.medians;
      total = data.strokes.length;
      writer = HanziWriter.create('target', entry.char, {
        width: dimensions(), height: dimensions(), padding: 18,
        showCharacter: true, showOutline: true, strokeColor: '#244b44', outlineColor: '#e3e7dd',
        strokeAnimationSpeed: slow ? 0.45 : 1, strokeFadeDuration: 0,
        charDataLoader: () => data,
        onLoadCharDataSuccess: () => {
          // Hanzi Writer finishes constructing the renderer after this callback.
          setTimeout(() => {
            if (token !== generation) return;
            ready = true; progress(); controls(); drawGuides();
            if (autoplay) play();
            else if (firstStroke) one(token);
          }, 0);
        },
        onLoadCharDataError: () => { if (token === generation) unavailable('Не удалось загрузить черты. Выберите знак снова.'); }
      });
    } catch (_) {
      if (token === generation) unavailable('Не удалось загрузить черты. Выберите знак снова.');
    }
  }
  async function one(token) {
    busy = true; controls();
    if (!cleared) { cleared = true; await writer.hideCharacter({duration: 0}); }
    if (token !== generation) return;
    activeGuide = step; drawGuides();
    await writer.animateStroke(step);
    if (token !== generation) return;
    step++; busy = false; progress();
    if (step >= total) { activeGuide = null; drawGuides(); }
    if (step >= total) pendingSteps = 0;
    controls();
    if (pendingSteps && token === generation) {
      pendingSteps--;
      await one(token);
    }
  }
  async function play() {
    if (!ready) return;
    if (running) { pause(); return; }
    if (paused) { paused = false; running = true; writer.resumeAnimation(); controls(); return; }
    if (busy) return;
    if (step >= total) { select(selected, true); return; }
    const token = generation;
    running = true; controls();
    while (running && step < total && token === generation) await one(token);
    if (token === generation && !paused) { running = false; controls(); }
  }
  function pause() {
    if (!writer || !running) return;
    writer.pauseAnimation(); paused = true; running = false; controls();
  }
  function nextStroke() {
    if (!ready) return;
    // Switch from autoplay to manual steps. Finish any in-flight stroke,
    // then honor taps in order, even if playback was paused.
    running = false;
    if (paused) { paused = false; writer.resumeAnimation(); }
    if (busy) {
      pendingSteps = Math.min(pendingSteps + 1, Math.max(0, total - step - 1));
      controls();
    } else if (step >= total) select(selected, false, true);
    else one(generation);
  }
  el('play').addEventListener('click', play);
  el('next').addEventListener('click', nextStroke);
  el('replay').addEventListener('click', () => select(selected, true));
  el('slow').addEventListener('click', () => { slow = !slow; select(selected, false); });
  el('show-guides').addEventListener('click', () => {
    showGuides = !showGuides; remember(); controls(); drawGuides();
  });
  function resizeWriter() {
    if (writer) writer.updateDimensions({width: dimensions(), height: dimensions(), padding: 18});
    drawGuides();
  }
  // The drawing can resize independently of the window (split screen, sidebar,
  // font scaling). Keep the rendered strokes aligned with their square grid.
  if (window.ResizeObserver) new ResizeObserver(resizeWriter).observe(el('stage'));
  else window.addEventListener('resize', resizeWriter);
  document.addEventListener('visibilitychange', () => { if (document.hidden) pause(); });
  let swipeStart = null;
  let suppressSwipeClick = false;
  document.addEventListener('pointerdown', event => {
    suppressSwipeClick = false;
    if (!event.isPrimary) { swipeStart = null; return; }
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    swipeStart = {x: event.clientX, y: event.clientY, id: event.pointerId};
  }, true);
  document.addEventListener('pointercancel', () => { swipeStart = null; }, true);
  document.addEventListener('pointerup', event => {
    if (!swipeStart || swipeStart.id !== event.pointerId) return;
    const dx = event.clientX - swipeStart.x, dy = event.clientY - swipeStart.y;
    swipeStart = null;
    if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    // A swipe that starts over a control must not also activate that control.
    suppressSwipeClick = true;
    event.preventDefault();
    const entry = characters[selected];
    if (!entry) return;
    // Stay within the selected written form, including at the first/last sign.
    const next = selected + (dx < 0 ? 1 : -1);
    if (characters[next] && characters[next].form === entry.form) select(next, false);
  }, true);
  document.addEventListener('click', event => {
    if (!suppressSwipeClick || event.detail === 0) return;
    suppressSwipeClick = false;
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);
  el('credits').addEventListener('toggle', async () => {
    if (!el('credits').open || el('licenses').childElementCount) return;
    try {
      const licenses = await (await fetch('licenses.json')).json();
      Object.entries(licenses).forEach(([name, text]) => {
        const heading = document.createElement('h2'); heading.textContent = name;
        const body = document.createElement('pre'); body.textContent = text;
        el('licenses').append(heading, body);
      });
    } catch (_) { el('licenses').textContent = 'Не удалось открыть лицензии.'; }
  });
  window.StrokeViewer = {
    pause,
    init(payload) {
      if (initialized) return;
      initialized = true;
      const state = new URLSearchParams(payload.state || '');
      slow = state.get('slow') === '1';
      showGuides = state.get('guides') === '1';
      el('word').textContent = (payload.forms || [])[0] || 'Написание';
      el('reading').textContent = payload.pinyin || '';
      (payload.forms || []).forEach((form, formIndex) => {
        const chars = Array.from(form).filter(c => c.trim());
        if (!chars.length) return;
        const group = document.createElement('div'); group.className = 'form';
        if (payload.forms.length > 1) {
          const label = document.createElement('p'); label.className = 'form-label'; label.textContent = form;
          group.append(label);
        }
        const chips = document.createElement('div'); chips.className = 'chips';
        chars.forEach((char, position) => {
          const index = characters.length;
          characters.push({char, position: position + 1, count: chars.length, form: formIndex});
          const button = document.createElement('button'); button.className = 'chip'; button.textContent = char;
          button.setAttribute('aria-label', char + ', знак ' + (position + 1) + ' из ' + chars.length);
          button.addEventListener('click', () => select(index, false));
          chips.append(button);
        });
        group.append(chips); el('characters').append(group);
      });
      const index = Number(state.get('i'));
      select(Number.isInteger(index) && index >= 0 && index < characters.length ? index : 0, false);
    }
  };
}());
