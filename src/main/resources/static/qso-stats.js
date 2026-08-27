/* ============================================================
   通联统计组件（QsoStats plugin）
   - 自动初始化页面上所有 .qso-stats-widget 容器
   - 从 /qso-stats/api/statistics（可用 data-endpoint 覆盖）拉取数据渲染
   - data-refresh="秒" 可开启自动刷新（默认关闭）
   - 呼号查询与一键 OQRS：
     * 查询接口 /qso-stats/api/search?callsign=XXX（可用 data-search-endpoint 覆盖）
     * 申请接口 /qso-stats/api/oqrs（可用 data-oqrs-endpoint 覆盖）
   依赖：无。兼容性：ES5 语法，支持旧浏览器。
   ============================================================ */
(function () {
  'use strict';

  var DEFAULT_ENDPOINT = '/qso-stats/api/statistics';
  var SEARCH_ENDPOINT = '/qso-stats/api/search';
  var OQRS_ENDPOINT = '/qso-stats/api/oqrs';
  var DASHBOARD_ENDPOINT = '/qso-stats/api/dashboard';
  var MIN_REFRESH = 30;

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmtNumber(n) {
    n = Number(n);
    if (!isFinite(n)) return '—';
    return n.toLocaleString('zh-CN');
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function card(section) {
    var cardEl = el('div', 'qso-stats__card');
    // 关键指标（通联总数 / 活跃度 / DXCC 字头）：加高亮修饰，突出核心数据
    if (section.type === 'number' || section.type === 'activity' || section.type === 'dxcc') {
      cardEl.className = 'qso-stats__card qso-stats__card--key';
    }
    cardEl.appendChild(el('p', 'qso-stats__card-title', section.title || ''));

    var body = el('div', 'qso-stats__card-body');
    switch (section.type) {
      case 'number':
        body.appendChild(numberView(section.value));
        break;
      case 'activity':
        body.appendChild(metricsView([
          ['今日', section.value && section.value.today],
          ['本月', section.value && section.value.month],
          ['今年', section.value && section.value.year]
        ]));
        break;
      case 'dxcc':
        body.appendChild(metricsView([
          ['已通联', section.value && section.value.worked],
          ['已确认', section.value && section.value.confirmed],
          ['可用', section.value && section.value.available]
        ]));
        break;
      case 'distribution':
        body.appendChild(distributionView(section.value));
        break;
      case 'recent':
        body.appendChild(recentView(section.value));
        break;
      default:
        body.appendChild(el('div', '', ''));
    }
    cardEl.appendChild(body);
    return cardEl;
  }

  function numberView(value) {
    var wrap = el('div', '');
    var num = el('div', 'qso-stats__number', fmtNumber(value && value.value));
    wrap.appendChild(num);
    return wrap;
  }

  function metricsView(items) {
    var wrap = el('div', 'qso-stats__metrics');
    items.forEach(function (item) {
      var metric = el('div', 'qso-stats__metric');
      metric.appendChild(el('span', 'qso-stats__metric-value', fmtNumber(item[1])));
      metric.appendChild(el('span', 'qso-stats__metric-label', item[0]));
      wrap.appendChild(metric);
    });
    return wrap;
  }

  function distributionView(rows) {
    var wrap = el('div', 'qso-stats__rows');
    (rows || []).forEach(function (row) {
      var rowEl = el('div', 'qso-stats__row');
      rowEl.appendChild(el('span', 'qso-stats__row-label', row.label));

      var bar = el('span', 'qso-stats__row-bar');
      var fill = el('span', 'qso-stats__row-bar-fill');
      var percent = Number(row.percent) || 0;
      fill.style.width = Math.min(100, Math.max(0, percent)) + '%';
      bar.appendChild(fill);

      rowEl.appendChild(bar);
      rowEl.appendChild(el('span', 'qso-stats__row-count',
        fmtNumber(row.count) + (isFinite(percent) ? ' · ' + percent.toFixed(1) + '%' : '')));
      wrap.appendChild(rowEl);
    });
    return wrap;
  }

  function recentView(rows) {
    var wrap = el('div', 'qso-stats__recent');
    (rows || []).forEach(function (row) {
      var rowEl = el('div', 'qso-stats__recent-row');
      rowEl.appendChild(el('span', 'qso-stats__recent-call', row.call));
      var meta = el('span', 'qso-stats__recent-meta');
      if (row.mode) {
        meta.appendChild(el('span', 'qso-stats__badge qso-stats__badge-mode', row.mode));
      }
      if (row.band) {
        meta.appendChild(el('span', 'qso-stats__badge qso-stats__badge-band', row.band));
      }
      rowEl.appendChild(meta);
      if (row.gridsquare) {
        rowEl.appendChild(el('span', 'qso-stats__recent-grid', row.gridsquare));
      }
      rowEl.appendChild(el('span', 'qso-stats__recent-time', row.time || ''));
      wrap.appendChild(rowEl);
    });
    if (!rows || !rows.length) {
      wrap.appendChild(el('div', 'qso-stats__empty', '暂无通联记录'));
    }
    return wrap;
  }

  /* ---------------- 呼号查询与一键 OQRS ---------------- */

  function searchSection(widgetRoot, root, maxResults) {
    var sectionEl = el('div', 'qso-stats__search');

    var head = el('div', 'qso-stats__search-head');
    var input = el('input', 'qso-stats__search-input');
    input.type = 'text';
    input.placeholder = '输入呼号查询通联记录，如 BG8LNG';
    input.maxLength = 32;
    var btn = el('button', 'qso-stats__search-btn', '查询');
    btn.type = 'button';
    head.appendChild(input);
    head.appendChild(btn);

    var body = el('div', 'qso-stats__search-body');
    var hint = el('div', 'qso-stats__search-hint',
      '按呼号检索本站通联记录（仅展示日期、模式、频段），可一键提交 OQRS 卡片申请。');
    body.appendChild(hint);

    sectionEl.appendChild(head);
    sectionEl.appendChild(body);
    root.appendChild(sectionEl);

    var searchEndpoint = widgetRoot.getAttribute('data-search-endpoint') || SEARCH_ENDPOINT;
    var oqrsEndpoint = widgetRoot.getAttribute('data-oqrs-endpoint') || OQRS_ENDPOINT;

    function doSearch() {
      var callsign = input.value.replace(/^\s+|\s+$/g, '').toUpperCase();
      if (!callsign) {
        renderSearchMessage(body, '请输入要查询的呼号', true);
        return;
      }
      renderSearchLoading(body);
      fetch(searchEndpoint + '?callsign=' + encodeURIComponent(callsign))
        .then(function (res) {
          if (!res.ok) {
            throw new Error('HTTP ' + res.status);
          }
          return res.json();
        })
        .then(function (data) {
          renderSearchResult(body, data, maxResults, oqrsEndpoint);
        })
        .catch(function (err) {
          renderSearchMessage(body,
            '查询失败：' + ((err && err.message) ? err.message : '接口请求异常'), true);
        });
    }

    btn.addEventListener('click', doSearch);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        doSearch();
      }
    });
    input.focus();
  }

  function renderSearchLoading(body) {
    body.innerHTML = '';
    var box = el('div', 'qso-stats__search-status qso-stats__search-loading', '正在查询…');
    body.appendChild(box);
  }

  function renderSearchMessage(body, text, isError) {
    body.innerHTML = '';
    var box = el('div', 'qso-stats__search-status' + (isError ? ' qso-stats__search-error'
      : ' qso-stats__search-empty'), text);
    body.appendChild(box);
  }

  function renderSearchResult(body, data, maxResults, oqrsEndpoint) {
    body.innerHTML = '';
    if (data.error) {
      renderSearchMessage(body, data.error, true);
      return;
    }
    var rows = data.qsos || [];
    if (!rows.length) {
      renderSearchMessage(body, '未找到呼号 ' + (data.callsign || '') + ' 的通联记录。', false);
      return;
    }

    var result = el('div', 'qso-stats__result');

    var table = el('table', 'qso-stats__result-table');
    var thead = document.createElement('thead');
    var headRow = document.createElement('tr');
    ['日期', '模式', '频段'].forEach(function (title) {
      var th = document.createElement('th');
      th.textContent = title;
      headRow.appendChild(th);
    });
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = document.createElement('tbody');
    rows.forEach(function (row) {
      var tr = document.createElement('tr');
      tr.appendChild(el('td', 'qso-stats__result-date', row.date || '—'));
      var modeTd = el('td', '');
      modeTd.appendChild(el('span', 'qso-stats__badge qso-stats__badge-mode', row.mode || '—'));
      tr.appendChild(modeTd);
      var bandTd = el('td', '');
      bandTd.appendChild(el('span', 'qso-stats__badge qso-stats__badge-band', row.band || '—'));
      tr.appendChild(bandTd);
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    result.appendChild(table);

    // 列表后方：条数汇总 + 一键 OQRS
    var foot = el('div', 'qso-stats__result-foot');
    var countNote = el('span', 'qso-stats__result-count',
      '共 ' + rows.length + ' 条' + (maxResults > 0 && rows.length >= maxResults
        ? '（已达查询上限 ' + maxResults + ' 条）' : ''));
    var oqrsBtn = el('button', 'qso-stats__oqrs-btn', '一键 OQRS 申请');
    oqrsBtn.type = 'button';
    foot.appendChild(countNote);
    foot.appendChild(oqrsBtn);
    result.appendChild(foot);
    body.appendChild(result);

    oqrsBtn.addEventListener('click', function () {
      renderOqrsForm(foot, {
        callsign: data.callsign,
        qsos: rows,
        endpoint: oqrsEndpoint
      });
    });
  }

  function renderOqrsForm(container, ctx) {
    container.innerHTML = '';

    var form = el('div', 'qso-stats__oqrs-form');
    form.appendChild(el('p', 'qso-stats__oqrs-title',
      '为 ' + ctx.callsign + ' 的 ' + ctx.qsos.length + ' 条通联申请 QSL 卡片'));

    var email = el('input', 'qso-stats__oqrs-input');
    email.type = 'email';
    email.placeholder = '您的邮箱地址（必填）';
    email.maxLength = 128;

    var routeRow = el('div', 'qso-stats__oqrs-routes');
    routeRow.appendChild(el('span', 'qso-stats__oqrs-route-label', '寄送方式：'));
    ['B', 'D'].forEach(function (value) {
      var label = el('label', 'qso-stats__oqrs-route');
      var radio = document.createElement('input');
      radio.type = 'radio';
      radio.name = 'qslroute';
      radio.value = value;
      if (value === 'B') {
        radio.checked = true;
      }
      label.appendChild(radio);
      label.appendChild(document.createTextNode(value === 'B' ? '卡片管理局（Bureau）' : '直邮（Direct）'));
      routeRow.appendChild(label);
    });

    var message = el('textarea', 'qso-stats__oqrs-input qso-stats__oqrs-message');
    message.placeholder = '备注留言（可选）';
    message.maxLength = 500;

    var actions = el('div', 'qso-stats__oqrs-actions');
    var submitBtn = el('button', 'qso-stats__oqrs-submit', '提交申请');
    submitBtn.type = 'button';
    var cancelBtn = el('button', 'qso-stats__oqrs-cancel', '取消');
    cancelBtn.type = 'button';
    actions.appendChild(submitBtn);
    actions.appendChild(cancelBtn);

    var status = el('div', 'qso-stats__oqrs-status');

    form.appendChild(email);
    form.appendChild(routeRow);
    form.appendChild(message);
    form.appendChild(actions);
    form.appendChild(status);
    container.appendChild(form);

    cancelBtn.addEventListener('click', function () {
      container.innerHTML = '';
      // 恢复按钮由外层保留：将原一键按钮重新挂回
      var btn = el('button', 'qso-stats__oqrs-btn', '一键 OQRS 申请');
      btn.type = 'button';
      container.appendChild(btn);
      btn.addEventListener('click', function () {
        renderOqrsForm(container, ctx);
      });
    });

    submitBtn.addEventListener('click', function () {
      var emailValue = email.value.replace(/^\s+|\s+$/g, '');
      if (!emailValue) {
        status.textContent = '请填写您的邮箱地址';
        status.className = 'qso-stats__oqrs-status qso-stats__oqrs-status-error';
        email.focus();
        return;
      }
      var routeValue = 'B';
      var radios = form.querySelectorAll('input[name="qslroute"]');
      for (var i = 0; i < radios.length; i++) {
        if (radios[i].checked) {
          routeValue = radios[i].value;
          break;
        }
      }
      var qsos = ctx.qsos.map(function (row) {
        return {
          date: row.date,
          time: row.time,
          band: row.band,
          mode: row.mode,
          stationId: row.stationId
        };
      });
      submitBtn.disabled = true;
      submitBtn.textContent = '提交中…';
      status.textContent = '';
      fetch(ctx.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          callsign: ctx.callsign,
          email: emailValue,
          message: message.value,
          qslroute: routeValue,
          qsos: qsos
        })
      })
        .then(function (res) {
          if (!res.ok) {
            throw new Error('HTTP ' + res.status);
          }
          return res.json();
        })
        .then(function (result) {
          if (result && result.success) {
            status.textContent = result.message || 'OQRS 卡片申请已提交，感谢使用！';
            status.className = 'qso-stats__oqrs-status qso-stats__oqrs-status-success';
            email.disabled = true;
            message.disabled = true;
            cancelBtn.textContent = '关闭';
          } else {
            status.textContent = (result && result.message) || '提交失败，请稍后再试';
            status.className = 'qso-stats__oqrs-status qso-stats__oqrs-status-error';
            submitBtn.disabled = false;
            submitBtn.textContent = '提交申请';
          }
        })
        .catch(function (err) {
          status.textContent = '提交失败：' + ((err && err.message) ? err.message : '接口请求异常');
          status.className = 'qso-stats__oqrs-status qso-stats__oqrs-status-error';
          submitBtn.disabled = false;
          submitBtn.textContent = '提交申请';
        });
    });
  }

  // 若页面已有与区块标题完全同文的标题（如文章/独立页标题），
  // 不再重复输出区块标题，避免「双重标题」。
  function headingMatches(root, title) {
    var text = String(title == null ? '' : title).replace(/\s+/g, ' ').trim();
    if (!text) {
      return false;
    }
    var headings = document.querySelectorAll('h1, h2, h3, h4');
    for (var i = 0; i < headings.length; i++) {
      var h = headings[i];
      if (root.contains(h)) {
        continue;
      }
      if ((h.textContent || '').replace(/\s+/g, ' ').trim() === text) {
        return true;
      }
    }
    return false;
  }


  /* ============================================================
     统计仪表盘（独立统计页面 /qso-stats）
     - 数据源：/qso-stats/api/dashboard（服务端已聚合日/月/历年/波段/模式）
     - 图表：ECharts（若未加载则回退为纯 CSS 条形分布）
     - 单页展示，无分页
     ============================================================ */

  var DASH_ACCENT = '#4096ff';
  var DASH_PALETTE = ['#4096ff', '#36cfc9', '#ffc53d', '#ff7a45', '#9254de',
    '#5cdbd3', '#ff4d4f', '#597ef7', '#ff9c6e', '#b37feb', '#13c2c2', '#f759ab'];
  var dashRoots = [];
  // 数据展示样式「默认主题」覆盖值：null = 跟随站点/系统（auto），true = 深色，false = 浅色
  var qsThemeOverride = null;

  // 图表容器尺寸变化时自动 resize，避免在主题进场动画/响应式断点切换后图表失真
  var chartResizeObserver = null;
  function getChartResizeObserver() {
    if (chartResizeObserver) {
      return chartResizeObserver;
    }
    if (typeof ResizeObserver === 'undefined') {
      return null;
    }
    chartResizeObserver = new ResizeObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) {
        var target = entries[i].target;
        var inst = window.echarts && window.echarts.getInstanceByDom
          ? window.echarts.getInstanceByDom(target) : null;
        if (inst && inst.resize) {
          inst.resize();
        }
      }
    });
    return chartResizeObserver;
  }

  function hexToRgba(hex, alpha) {
    hex = String(hex || '').replace('#', '');
    if (hex.length === 3) {
      hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
    }
    var n = parseInt(hex, 16);
    if (isNaN(n)) {
      return 'rgba(64,150,255,' + alpha + ')';
    }
    var r = (n >> 16) & 255;
    var g = (n >> 8) & 255;
    var b = n & 255;
    return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
  }

  function isDarkMode() {
    if (qsThemeOverride !== null) {
      return qsThemeOverride === true;
    }
    var root = document.documentElement;
    if (root.classList.contains('dark')) {
      return true;
    }
    if (root.classList.contains('light')) {
      return false;
    }
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  function dashText() {
    return isDarkMode() ? 'rgba(255,255,255,0.88)' : '#3d4757';
  }

  function dashSubText() {
    return isDarkMode() ? 'rgba(255,255,255,0.55)' : '#8a94a6';
  }

  function dashAxis() {
    return isDarkMode() ? 'rgba(255,255,255,0.14)' : '#e3e8ef';
  }

  function dashGrid() {
    return isDarkMode() ? 'rgba(255,255,255,0.05)' : 'rgba(64,150,255,0.06)';
  }

  function kpiCard(title, value, sub, opts) {
    opts = opts || {};
    var cardEl = el('div', 'qs-dash__kpi' + (opts.key ? ' qs-dash__kpi--key' : ''));
    cardEl.setAttribute('data-tone', opts.tone || 'blue');
    if (opts.color) {
      cardEl.style.setProperty('--qs-kpi-color', opts.color);
    }
    var head = el('div', 'qs-dash__kpi-head');
    head.appendChild(el('span', 'qs-dash__kpi-title', title));
    head.appendChild(el('span', 'qs-dash__kpi-spark', ''));
    cardEl.appendChild(head);
    var num = el('div', 'qs-dash__kpi-value', fmtNumber(value));
    if (sub) {
      num.appendChild(el('span', 'qs-dash__kpi-sub', sub));
    }
    cardEl.appendChild(num);
    return cardEl;
  }

  function dashPanel(title, chartKey, listKey, full) {
    var panel = el('section', 'qs-dash__panel' + (full ? ' qs-dash__panel--full' : ''));
    panel.appendChild(el('h3', 'qs-dash__panel-title', title));
    if (chartKey) {
      var chart = el('div', 'qs-dash__chart' + (chartKey === 'mode' ? ' qs-dash__chart--donut' : ''));
      chart.setAttribute('data-dash-chart', chartKey);
      panel.appendChild(chart);
    }
    if (listKey) {
      var list = el('ul', 'qs-dash__list');
      list.setAttribute('data-dash-list', listKey);
      panel.appendChild(list);
    }
    return panel;
  }

  function barOption(categories, counts, opts) {
    opts = opts || {};
    var base = opts.color || DASH_ACCENT;
    var gradient = {
      type: 'linear',
      x: 0, y: 0, x2: 0, y2: 1,
      colorStops: [
        { offset: 0, color: hexToRgba(base, 0.95) },
        { offset: 1, color: hexToRgba(base, 0.40) }
      ]
    };
    return {
      animationDuration: 700,
      animationEasing: 'cubicOut',
      grid: { left: 42, right: 14, top: 28, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDarkMode() ? 'rgba(30,34,42,0.92)' : 'rgba(255,255,255,0.98)',
        borderColor: hexToRgba(base, 0.35),
        textStyle: { color: dashText(), fontSize: 12 },
        axisPointer: { type: 'shadow', shadowStyle: { color: dashGrid() } }
      },
      xAxis: {
        type: 'category',
        data: categories,
        axisLine: { lineStyle: { color: dashAxis() } },
        axisTick: { show: false },
        axisLabel: { color: dashSubText(), fontSize: 11, interval: opts.xInterval || 0 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: dashAxis() } },
        axisLabel: { color: dashSubText(), fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: counts.map(function (c) {
          return { value: c, itemStyle: { borderRadius: [6, 6, 0, 0] } };
        }),
        barWidth: opts.barWidth || '58%',
        barMaxWidth: 34,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: opts.gradient === false ? base : gradient
        },
        emphasis: {
          itemStyle: { color: hexToRgba(base, 1), shadowBlur: 10, shadowColor: hexToRgba(base, 0.4) }
        }
      }]
    };
  }

  function donutOption(labels, values) {
    return {
      animationDuration: 500,
      color: DASH_PALETTE,
      tooltip: {
        trigger: 'item',
        backgroundColor: isDarkMode() ? 'rgba(30,34,42,0.92)' : 'rgba(255,255,255,0.96)',
        borderColor: dashAxis(),
        textStyle: { color: dashText(), fontSize: 12 }
      },
      legend: {
        orient: 'vertical',
        right: 0,
        top: 'middle',
        itemWidth: 10,
        itemHeight: 10,
        icon: 'circle',
        textStyle: { color: dashSubText(), fontSize: 11 }
      },
      series: [{
        type: 'pie',
        radius: ['48%', '74%'],
        center: ['36%', '50%'],
        avoidLabelOverlap: true,
        padAngle: 1,
        minAngle: 2,
        itemStyle: {
          borderRadius: 6,
          borderColor: isDarkMode() ? '#1e222a' : '#fff',
          borderWidth: 3,
          shadowBlur: 14,
          shadowColor: isDarkMode() ? 'rgba(0,0,0,0.4)' : 'rgba(22,119,255,0.15)'
        },
        label: {
          show: true,
          formatter: '{d}%',
          color: dashSubText(),
          fontSize: 10,
          fontWeight: 600
        },
        labelLine: { length: 10, length2: 8, lineStyle: { color: dashAxis() } },
        emphasis: {
          scale: true,
          scaleSize: 6,
          itemStyle: { shadowBlur: 22, shadowColor: 'rgba(0,0,0,0.30)' }
        },
        data: labels.map(function (l, i) {
          return { name: l, value: values[i] };
        })
      }]
    };
  }

  function hbarOption(labels, counts, opts) {
    opts = opts || {};
    var base = opts.color || DASH_ACCENT;
    return {
      animationDuration: 700,
      animationEasing: 'cubicOut',
      grid: { left: 8, right: 44, top: 10, bottom: 24 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow', shadowStyle: { color: dashGrid() } },
        backgroundColor: isDarkMode() ? 'rgba(30,34,42,0.92)' : 'rgba(255,255,255,0.98)',
        borderColor: hexToRgba(base, 0.35),
        textStyle: { color: dashText(), fontSize: 12 }
      },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: dashAxis() } },
        axisLabel: { color: dashSubText(), fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        data: labels,
        axisLine: { lineStyle: { color: dashAxis() } },
        axisTick: { show: false },
        axisLabel: { color: dashText(), fontSize: 11 }
      },
      series: [{
        type: 'bar',
        data: counts.map(function (c) {
          return { value: c };
        }),
        barWidth: '62%',
        barMaxWidth: 22,
        itemStyle: {
          borderRadius: [0, 6, 6, 0],
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 1, y2: 0,
            colorStops: [
              { offset: 0, color: hexToRgba(base, 0.40) },
              { offset: 1, color: hexToRgba(base, 0.95) }
            ]
          }
        },
        emphasis: { itemStyle: { shadowBlur: 12, shadowColor: hexToRgba(base, 0.45) } }
      }]
    };
  }

  /**
   * 稳健地初始化图表：只有等容器真正具备有效尺寸时才 init ECharts，
   * 避免在样式未就绪 / 布局未稳定 / 主题进场动画进行中时以 0 尺寸渲染成空白。
   * 若容器迟迟没有尺寸或 ECharts 不可用、初始化异常，则回退为纯 CSS 展示，
   * 确保面板绝不出现「空白无数据」。
   */
  function renderDashChart(container, option, fallback) {
    if (!container) {
      if (fallback) {
        fallback();
      }
      return null;
    }
    if (!window.echarts) {
      if (fallback) {
        fallback();
      }
      return null;
    }
    var tries = 0;
    var MAX_TRIES = 90; // ~1.5s@60fps，足够覆盖主题进场动画与样式加载
    function attempt() {
      var w = container.clientWidth;
      var h = container.clientHeight;
      if (!w || !h) {
        tries++;
        if (tries <= MAX_TRIES) {
          requestAnimationFrame(attempt);
          return null;
        }
        if (fallback) {
          fallback();
        }
        return null;
      }
      var chart = null;
      try {
        var existing = window.echarts.getInstanceByDom
          ? window.echarts.getInstanceByDom(container) : null;
        if (existing) {
          existing.setOption(option, true);
          chart = existing;
        } else {
          chart = window.echarts.init(container, null, { renderer: 'canvas' });
          chart.setOption(option);
        }
        var ro = getChartResizeObserver();
        if (ro) {
          ro.observe(container);
        }
      } catch (e) {
        // 初始化异常（如过渡期间容器被替换）：灰条回退，绝不空白
        if (fallback) {
          fallback();
        }
        return null;
      }
      // 首帧后再补一次 resize，确保 canvas 拉满容器
      requestAnimationFrame(function () {
        if (chart && chart.resize) {
          chart.resize();
        }
      });
      return chart;
    }
    return attempt();
  }

  function dashBarFallback(container, items) {
    // 无 ECharts 时的 CSS 回退：复用分布条样式
    if (!container || !items || !items.length) {
      return;
    }
    var wrap = el('div', 'qso-stats__rows');
    var max = 1;
    items.forEach(function (it) {
      if (it.count > max) {
        max = it.count;
      }
    });
    items.forEach(function (it) {
      var rowEl = el('div', 'qso-stats__row');
      rowEl.appendChild(el('span', 'qso-stats__row-label', it.label));
      var bar = el('span', 'qso-stats__row-bar');
      var fill = el('span', 'qso-stats__row-bar-fill');
      fill.style.width = Math.min(100, Math.round(it.count * 100 / max)) + '%';
      bar.appendChild(fill);
      rowEl.appendChild(bar);
      rowEl.appendChild(el('span', 'qso-stats__row-count', fmtNumber(it.count)));
      wrap.appendChild(rowEl);
    });
    container.appendChild(wrap);
  }

  function dashPieFallback(container, items) {
    if (!container || !items || !items.length) {
      return;
    }
    var wrap = el('div', 'qso-stats__rows');
    items.forEach(function (it, i) {
      var rowEl = el('div', 'qso-stats__row');
      rowEl.appendChild(el('span', 'qso-stats__row-label', it.label));
      var bar = el('span', 'qso-stats__row-bar');
      var fill = el('span', 'qso-stats__row-bar-fill');
      fill.style.width = Math.min(100, it.percent || 0) + '%';
      bar.appendChild(fill);
      rowEl.appendChild(bar);
      rowEl.appendChild(el('span', 'qso-stats__row-count', fmtNumber(it.count)));
      wrap.appendChild(rowEl);
    });
    container.appendChild(wrap);
  }

  function dashList(listEl, items, max) {
    if (!listEl) {
      return;
    }
    listEl.innerHTML = '';
    var top = max && items.length > max ? items.slice(0, max) : items;
    var peak = 1;
    top.forEach(function (it) {
      if (it.count > peak) {
        peak = it.count;
      }
    });
    top.forEach(function (it) {
      var li = el('li', 'qs-dash__list-item');
      li.appendChild(el('span', 'qs-dash__list-label', it.label));
      var bar = el('span', 'qs-dash__list-bar');
      var fill = el('span', 'qs-dash__list-fill');
      fill.style.width = Math.min(100, Math.round(it.count * 100 / peak)) + '%';
      bar.appendChild(fill);
      li.appendChild(bar);
      li.appendChild(el('span', 'qs-dash__list-count', fmtNumber(it.count)));
      listEl.appendChild(li);
    });
  }

  function percentOf(count, total) {
    return total > 0 ? Math.round(count * 1000 / total) / 10 : 0;
  }

  function renderDashRecent(panel, rows) {
    var wrap = el('div', 'qso-stats__recent');
    (rows || []).forEach(function (row) {
      var rowEl = el('div', 'qso-stats__recent-row');
      rowEl.appendChild(el('span', 'qso-stats__recent-call', row.call));
      var meta = el('span', 'qso-stats__recent-meta');
      if (row.mode) {
        meta.appendChild(el('span', 'qso-stats__badge qso-stats__badge-mode', row.mode));
      }
      if (row.band) {
        meta.appendChild(el('span', 'qso-stats__badge qso-stats__badge-band', row.band));
      }
      rowEl.appendChild(meta);
      if (row.gridsquare) {
        rowEl.appendChild(el('span', 'qso-stats__recent-grid', row.gridsquare));
      }
      rowEl.appendChild(el('span', 'qso-stats__recent-time', row.time || ''));
      wrap.appendChild(rowEl);
    });
    if (!rows || !rows.length) {
      wrap.appendChild(el('div', 'qso-stats__empty', '暂无通联记录'));
    }
    panel.appendChild(wrap);
  }

  function areaOption(labels, counts, color) {
    return {
      animationDuration: 800,
      animationEasing: 'cubicOut',
      grid: { left: 42, right: 16, top: 28, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: isDarkMode() ? 'rgba(19,26,42,0.94)' : 'rgba(255,255,255,0.98)',
        borderColor: hexToRgba(color, 0.35),
        textStyle: { color: dashText(), fontSize: 12 },
        axisPointer: { type: 'line', lineStyle: { color: hexToRgba(color, 0.4) } }
      },
      xAxis: {
        type: 'category', data: labels,
        axisLine: { lineStyle: { color: dashAxis() } },
        axisTick: { show: false },
        axisLabel: { color: dashSubText(), fontSize: 11, interval: 4 }
      },
      yAxis: {
        type: 'value', minInterval: 1,
        splitLine: { lineStyle: { color: dashAxis() } },
        axisLabel: { color: dashSubText(), fontSize: 11 }
      },
      series: [{
        type: 'line', data: counts, smooth: 0.4, symbol: 'circle', symbolSize: 5,
        showSymbol: false,
        lineStyle: { width: 2.5, color: color },
        itemStyle: { color: color, borderColor: '#fff', borderWidth: 1.5 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: hexToRgba(color, 0.28) },
            { offset: 1, color: hexToRgba(color, 0.02) }] } },
        emphasis: { focus: 'series' }
      }]
    };
  }

  function modernDonutOption(labels, values, total) {
    var base = MODERN_TONES.violet;
    return {
      animationDuration: 600,
      color: ['#2f6bff', '#22d3ee', '#7c5cff', '#f59e0b', '#ff6b4a', '#10b981', '#94a3b8'],
      tooltip: {
        trigger: 'item',
        backgroundColor: isDarkMode() ? 'rgba(19,26,42,0.94)' : 'rgba(255,255,255,0.98)',
        borderColor: hexToRgba(base, 0.35),
        textStyle: { color: dashText(), fontSize: 12 }
      },
      series: [{
        type: 'pie', radius: ['58%', '82%'], center: ['50%', '50%'],
        padAngle: 2, minAngle: 3,
        itemStyle: { borderRadius: 8,
          borderColor: isDarkMode() ? '#131a2a' : '#ffffff', borderWidth: 3 },
        label: { show: true, position: 'center',
          formatter: fmtNumber(total) + '\n总通联',
          color: dashText(), fontSize: 17, fontWeight: 800, lineHeight: 24 },
        emphasis: { scale: true, scaleSize: 5,
          itemStyle: { shadowBlur: 18, shadowColor: 'rgba(15,23,42,0.25)' } },
        data: labels.map(function (l, i) { return { name: l, value: values[i] }; })
      }]
    };
  }

  function modernModeLegend(listEl, items, total) {
    if (!listEl) {
      return;
    }
    listEl.innerHTML = '';
    var palette = ['#2f6bff', '#22d3ee', '#7c5cff', '#f59e0b', '#ff6b4a', '#10b981', '#94a3b8'];
    (items || []).forEach(function (m, i) {
      var li = el('li', 'qs-dash__legend-item');
      var sw = el('span', 'qs-dash__legend-sw');
      sw.style.background = palette[i % palette.length];
      li.appendChild(sw);
      li.appendChild(el('span', 'qs-dash__legend-label', m.label));
      li.appendChild(el('span', 'qs-dash__legend-count', fmtNumber(m.count)));
      li.appendChild(el('span', 'qs-dash__legend-pct',
        (total > 0 ? (m.count * 100 / total).toFixed(1) : '0.0') + '%'));
      listEl.appendChild(li);
    });
  }

  function renderDashCharts(root, stats) {
    if (!stats) {
      return;
    }
    var totalQso = stats.total;
    var modern = !!root.querySelector('.qs-dash--modern');
    var mItems = stats.byMode || [];
    var bItems = stats.byBand || [];

    // 日统计（蓝 · 面积图）
    var dayChart = root.querySelector('[data-dash-chart="day"]');
    if (dayChart) {
      var dayLabels = (stats.byDay || []).map(function (p) { return p.label; });
      var dayCounts = (stats.byDay || []).map(function (p) { return p.count; });
      var dayOpt = modern
        ? areaOption(dayLabels, dayCounts, MODERN_TONES.blue)
        : barOption(dayLabels, dayCounts, { xInterval: 4, color: '#4096ff' });
      renderDashChart(dayChart, dayOpt, function () { dashBarFallback(dayChart, stats.byDay || []); });
    }

    // 月统计（青）
    var monthChart = root.querySelector('[data-dash-chart="month"]');
    if (monthChart) {
      renderDashChart(monthChart, barOption(
        (stats.byMonth || []).map(function (p) { return p.label; }),
        (stats.byMonth || []).map(function (p) { return p.count; }),
        { xInterval: 0, color: modern ? MODERN_TONES.cyan : '#36cfc9' }), function () {
        dashBarFallback(monthChart, stats.byMonth || []);
      });
    }

    // 历年统计（绿 / 紫）
    var yearChart = root.querySelector('[data-dash-chart="year"]');
    if (yearChart) {
      renderDashChart(yearChart, barOption(
        (stats.byYear || []).map(function (p) { return p.label; }),
        (stats.byYear || []).map(function (p) { return p.count; }),
        { xInterval: 0, barWidth: '40%', color: modern ? MODERN_TONES.green : '#9254de' }), function () {
        dashBarFallback(yearChart, stats.byYear || []);
      });
    }

    // 模式分布（环形图）
    var modeChart = root.querySelector('[data-dash-chart="mode"]');
    if (modeChart) {
      var modeOpt = modern
        ? modernDonutOption(mItems.map(function (p) { return p.label; }),
            mItems.map(function (p) { return p.count; }), totalQso)
        : donutOption(mItems.map(function (p) { return p.label; }),
            mItems.map(function (p) { return p.count; }));
      renderDashChart(modeChart, modeOpt, function () {
        dashPieFallback(modeChart, mItems.map(function (p) {
          return { label: p.label, count: p.count, percent: percentOf(p.count, totalQso) };
        }));
      });
    }
    if (modern) {
      modernModeLegend(root.querySelector('[data-dash-legend="mode"]'), mItems, totalQso);
    } else {
      dashList(root.querySelector('[data-dash-list="mode"]'), mItems, 12);
    }

    // 频段分布（横向条形，橙）
    var bandChart = root.querySelector('[data-dash-chart="band"]');
    if (bandChart) {
      var topBands = bItems.slice(0, 14);
      renderDashChart(bandChart, hbarOption(
        topBands.map(function (p) { return p.label; }),
        topBands.map(function (p) { return p.count; }),
        { color: modern ? MODERN_TONES.coral : '#ff7a45' }), function () {
        dashBarFallback(bandChart, bItems.map(function (p) {
          return { label: p.label, count: p.count };
        }));
      });
    }
    if (!modern) {
      dashList(root.querySelector('[data-dash-list="band"]'), bItems, 12);
    }
  }

  var DEFAULT_LAYOUT = [
    { key: 'search', span: 2 },
    { key: 'kpi', span: 2 },
    { key: 'day', span: 1 },
    { key: 'month', span: 1 },
    { key: 'mode', span: 1 },
    { key: 'band', span: 1 },
    { key: 'year', span: 1 },
    { key: 'recent', span: 1 }
  ];

  function normalizeLayout(layout) {
    if (!layout || !layout.length) {
      return DEFAULT_LAYOUT.slice();
    }
    var out = [];
    layout.forEach(function (item) {
      if (item && item.enabled !== false) {
        out.push({ key: String(item.key || ''), span: Number(item.span) === 2 ? 2 : 1 });
      }
    });
    return out.length ? out : DEFAULT_LAYOUT.slice();
  }

  function kpisView(stats) {
    var kpis = el('div', 'qs-dash__kpis');
    kpis.appendChild(kpiCard('通联总数', stats.total, '全部 QSO', { key: true, tone: 'blue' }));
    kpis.appendChild(kpiCard('今日', stats.today, 'UTC 自然日', { tone: 'green' }));
    kpis.appendChild(kpiCard('本月', stats.month, stats.year + ' 年 ' + (new Date().getMonth() + 1) + ' 月', { tone: 'cyan' }));
    kpis.appendChild(kpiCard('今年', stats.yearQso, stats.year + ' 年度', { tone: 'violet' }));
    kpis.appendChild(kpiCard('DXCC 已确认', stats.dxccConfirmed, stats.dxccWorked + ' 已通联', { tone: 'coral' }));
    kpis.appendChild(kpiCard('DXCC 可用', stats.dxccAvailable, '字头总量', { tone: 'amber' }));
    return kpis;
  }

  /* ============================================================
     现代仪表盘（qso-stats-UI设计图 · Bento + 贯穿横幅 + 玻璃拟态）
     - displayStyle === 'modern'（默认）时使用；'classic' 走 renderClassicDashboard
     - 数据源与 /qso-stats/api/dashboard 一致
     ============================================================ */
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var MODERN_TONES = {
    blue: '#2f6bff', cyan: '#22d3ee', violet: '#7c5cff',
    coral: '#ff6b4a', green: '#10b981', amber: '#f59e0b'
  };

  function pageTitle() {
    var t = '通联统计';
    var elm = document.querySelector('.qso-stats-page__title');
    if (elm && elm.textContent && elm.textContent.trim()) {
      t = elm.textContent.trim();
    }
    return t;
  }

  function layoutHas(layout, key) {
    for (var i = 0; i < layout.length; i++) {
      if (layout[i].key === key) {
        return true;
      }
    }
    return false;
  }

  function modernHeroEl(title) {
    var hero = document.createElement('section');
    hero.className = 'qs-dash__hero';
    hero.innerHTML =
      '<svg class="qs-dash__hero-art" viewBox="0 0 1440 420" preserveAspectRatio="xMidYMid slice" aria-hidden="true">' +
        '<defs><linearGradient id="qshg" x1="0" y1="0" x2="1" y2="0">' +
          '<stop offset="0" stop-color="#5b8dff" stop-opacity="0.55"/>' +
          '<stop offset="1" stop-color="#22d3ee" stop-opacity="0.25"/></linearGradient>' +
        '<pattern id="qshgp" width="46" height="46" patternUnits="userSpaceOnUse">' +
          '<path d="M46 0H0V46" fill="none" stroke="rgba(255,255,255,0.05)" stroke-width="1"/></pattern></defs>' +
        '<rect width="1440" height="420" fill="url(#qshgp)"/>' +
        '<g fill="none" stroke="url(#qshg)" stroke-width="1.6">' +
          '<circle cx="1220" cy="60" r="70" opacity="0.9"/>' +
          '<circle cx="1220" cy="60" r="150" opacity="0.65"/>' +
          '<circle cx="1220" cy="60" r="240" opacity="0.45"/>' +
          '<circle cx="1220" cy="60" r="340" opacity="0.3"/>' +
          '<circle cx="1220" cy="60" r="450" opacity="0.18"/>' +
          '<circle cx="1220" cy="60" r="570" opacity="0.1"/></g>' +
        '<circle cx="1220" cy="60" r="7" fill="#8fb5ff"/>' +
        '<circle cx="1220" cy="60" r="16" fill="none" stroke="#8fb5ff" stroke-opacity="0.5"/>' +
        '<g fill="rgba(255,255,255,0.22)">' +
          '<rect x="80" y="330" width="34" height="5" rx="2.5"/>' +
          '<circle cx="130" cy="332.5" r="3.4"/><circle cx="146" cy="332.5" r="3.4"/>' +
          '<rect x="162" y="330" width="34" height="5" rx="2.5"/></g>' +
        '<text x="80" y="366" fill="rgba(255,255,255,0.16)" font-size="12" letter-spacing="6" ' +
          'font-family="ui-monospace, monospace">CQ CQ CQ DE · 73</text>' +
      '</svg>' +
      '<div class="qs-dash__hero-inner">' +
        '<span class="qs-dash__hero-kicker">HAM RADIO · QSO DASHBOARD</span>' +
        '<h1 class="qs-dash__hero-title">' + esc(title || '通联统计') +
          ' <span class="qs-dash__hero-grad">QSO Statistics</span></h1>' +
        '<p class="qs-dash__hero-sub">业余无线电通联数据一览 —— 实时同步自 Wavelog 日志平台，' +
          '涵盖通联趋势、模式与频段分布、DXCC 进度。</p>' +
        '<div class="qs-dash__hero-chips">' +
          '<span class="qs-dash__hero-chip">SSB / FT8 / CW</span>' +
          '<span class="qs-dash__hero-chip">Wavelog API 数据源</span></div>' +
      '</div>' +
      '<div class="qs-dash__hero-fade"></div>';
    return hero;
  }

  function modernSectionHead(iconPath, title, note) {
    var head = el('div', 'qs-dash__section-head');
    if (iconPath) {
      var ico = el('span', 'qs-dash__section-ico');
      var svg = document.createElementNS(SVG_NS, 'svg');
      svg.setAttribute('width', '15');
      svg.setAttribute('height', '15');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('fill', 'none');
      svg.setAttribute('stroke', 'currentColor');
      svg.setAttribute('stroke-width', '2');
      svg.setAttribute('stroke-linecap', 'round');
      var p = document.createElementNS(SVG_NS, 'path');
      p.setAttribute('d', iconPath);
      svg.appendChild(p);
      ico.appendChild(svg);
      head.appendChild(ico);
    }
    head.appendChild(el('h2', '', title));
    if (note) {
      head.appendChild(el('span', 'qs-dash__section-note', note));
    }
    return head;
  }

  function modernKpiTop(label, tone) {
    var top = el('div', 'qs-dash__kpi-top');
    top.appendChild(el('span', 'qs-dash__kpi-label', label));
    var ico = el('span', 'qs-dash__kpi-ico');
    ico.setAttribute('data-tone', tone || 'blue');
    top.appendChild(ico);
    return top;
  }

  function modernKpi(label, value, sub, tone) {
    var card = el('article', 'qs-dash__kpi');
    card.setAttribute('data-tone', tone || 'blue');
    card.appendChild(modernKpiTop(label, tone));
    card.appendChild(el('div', 'qs-dash__kpi-value', fmtNumber(value)));
    if (sub) {
      card.appendChild(el('div', 'qs-dash__kpi-sub', sub));
    }
    return card;
  }

  function modernDxccKpi(stats) {
    var card = el('article', 'qs-dash__kpi');
    card.setAttribute('data-tone', 'coral');
    card.appendChild(modernKpiTop('DXCC 进度', 'coral'));
    card.appendChild(el('div', 'qs-dash__kpi-value', fmtNumber(stats.dxccConfirmed)));
    card.appendChild(el('div', 'qs-dash__kpi-sub',
      '已确认 · 已通联 ' + fmtNumber(stats.dxccWorked)));
    var progress = el('div', 'qs-dash__kpi-progress');
    var fill = el('span', 'qs-dash__kpi-progress-fill');
    progress.appendChild(fill);
    card.appendChild(progress);
    card.appendChild(el('div', 'qs-dash__kpi-sub qs-dash__kpi-sub--tiny',
      '共 ' + fmtNumber(stats.dxccAvailable) + ' 个可用字头'));
    var pct = stats.dxccAvailable > 0
      ? Math.min(100, stats.dxccConfirmed * 100 / stats.dxccAvailable) : 0;
    requestAnimationFrame(function () { fill.style.width = pct + '%'; });
    return card;
  }

  function modernSparkline(byDay) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'qs-dash__kpi-spark');
    svg.setAttribute('viewBox', '0 0 200 54');
    svg.setAttribute('preserveAspectRatio', 'none');
    var counts = (byDay || []).map(function (p) { return Number(p.count) || 0; });
    if (!counts.length) {
      return svg;
    }
    var max = Math.max.apply(null, counts) || 1;
    var W = 200, H = 54;
    var pts = counts.map(function (v, i) {
      return [(i * W / (counts.length - 1)).toFixed(1),
        (H - 6 - v * (H - 14) / max).toFixed(1)];
    });
    var line = 'M' + pts.map(function (p) { return p.join(','); }).join(' L');
    var fill = document.createElementNS(SVG_NS, 'path');
    fill.setAttribute('d', line + ' L200,' + H + ' L0,' + H + ' Z');
    fill.setAttribute('fill', 'rgba(64,150,255,0.18)');
    var lineP = document.createElementNS(SVG_NS, 'path');
    lineP.setAttribute('d', line);
    lineP.setAttribute('fill', 'none');
    lineP.setAttribute('stroke', '#4096ff');
    lineP.setAttribute('stroke-width', '2');
    lineP.setAttribute('stroke-linecap', 'round');
    svg.appendChild(fill);
    svg.appendChild(lineP);
    return svg;
  }

  function modernKpis(stats) {
    var kpis = el('div', 'qs-dash__kpis qs-dash__kpis--bento');
    var feature = el('article', 'qs-dash__kpi qs-dash__kpi--feature');
    feature.setAttribute('data-tone', 'blue');
    feature.appendChild(modernKpiTop('通联总数 · TOTAL QSO', 'blue'));
    feature.appendChild(el('div', 'qs-dash__kpi-value', fmtNumber(stats.total)));
    feature.appendChild(el('div', 'qs-dash__kpi-sub', '全部 QSO · 持续累积'));
    feature.appendChild(modernSparkline(stats.byDay));
    kpis.appendChild(feature);
    kpis.appendChild(modernKpi('今日', stats.today, 'UTC 自然日', 'green'));
    kpis.appendChild(modernKpi('本月', stats.month,
      stats.year + ' 年 ' + (new Date().getMonth() + 1) + ' 月', 'cyan'));
    kpis.appendChild(modernKpi('今年', stats.yearQso, stats.year + ' 年度', 'violet'));
    kpis.appendChild(modernDxccKpi(stats));
    return kpis;
  }

  function modernPanelHead(title, cap, tone) {
    var head = el('div', 'qs-dash__panel-head');
    var dot = el('span', 'qs-dash__panel-dot');
    dot.setAttribute('data-tone', tone || 'blue');
    head.appendChild(dot);
    head.appendChild(el('h3', '', title));
    if (cap) {
      head.appendChild(el('span', 'qs-dash__panel-cap', cap));
    }
    return head;
  }

  function modernPanel(title, chartKey, cap, tone, full) {
    var panel = el('article', 'qs-dash__panel' + (full ? ' qs-dash__panel--full' : ''));
    panel.setAttribute('data-tone', tone || 'blue');
    panel.appendChild(modernPanelHead(title, cap, tone));
    if (chartKey) {
      var chart = el('div', 'qs-dash__chart' + (chartKey === 'mode' ? ' qs-dash__chart--donut' : ''));
      chart.setAttribute('data-dash-chart', chartKey);
      panel.appendChild(chart);
    }
    return panel;
  }

  function modernModePanel(full) {
    var panel = el('article', 'qs-dash__panel' + (full ? ' qs-dash__panel--full' : ''));
    panel.setAttribute('data-tone', 'violet');
    panel.appendChild(modernPanelHead('模式分布', '环形图 + 明细', 'violet'));
    var wrap = el('div', 'qs-dash__donut-wrap');
    var chart = el('div', 'qs-dash__chart qs-dash__chart--tall');
    chart.setAttribute('data-dash-chart', 'mode');
    wrap.appendChild(chart);
    var legend = el('ul', 'qs-dash__legend');
    legend.setAttribute('data-dash-legend', 'mode');
    wrap.appendChild(legend);
    panel.appendChild(wrap);
    return panel;
  }

  function modernRecentPanel(full, rows) {
    var panel = el('article', 'qs-dash__panel' + (full ? ' qs-dash__panel--full' : ''));
    panel.setAttribute('data-tone', 'amber');
    panel.appendChild(modernPanelHead('最近通联', '最新通联', 'amber'));
    renderDashRecent(panel, rows || []);
    return panel;
  }

  function modernPanels(layout, stats, data) {
    var grid = el('div', 'qs-dash__panels');
    layout.forEach(function (item) {
      var full = item.span === 2;
      switch (item.key) {
        case 'day':
          grid.appendChild(modernPanel('近 30 日通联', 'day', '日统计 · 面积图', 'blue', full));
          break;
        case 'month':
          grid.appendChild(modernPanel('本年每月通联', 'month', '月统计 · 柱状图', 'cyan', full));
          break;
        case 'mode':
          grid.appendChild(modernModePanel(full));
          break;
        case 'band':
          grid.appendChild(modernPanel('频段分布', 'band', '横向条形 · TOP', 'coral', full));
          break;
        case 'year':
          grid.appendChild(modernPanel('历年通联', 'year', '年度汇总', 'green', full));
          break;
        case 'recent':
          grid.appendChild(modernRecentPanel(full, data.recent || []));
          break;
      }
    });
    return grid;
  }

  function modernFoot(updatedAt) {
    var foot = el('div', 'qs-dash__foot');
    foot.appendChild(el('span', 'qs-dash__live', ''));
    foot.appendChild(el('span', 'qs-dash__foot-text',
      updatedAt ? '更新于 ' + String(updatedAt).replace('T', ' ').slice(0, 16) + ' UTC' : '更新于 —'));
    foot.appendChild(el('span', 'qs-dash__foot-src', '数据源：Wavelog API v2'));
    return foot;
  }

  function renderModernDashboard(root, data) {
    root.innerHTML = '';
    var wrap = el('div', 'qso-stats qso-stats--dash qs-dash--modern');
    // 默认主题：auto -> 跟随站点/系统；light/dark -> 强制
    if (data.defaultTheme === 'light') {
      qsThemeOverride = false;
    } else if (data.defaultTheme === 'dark') {
      qsThemeOverride = true;
    } else {
      qsThemeOverride = null;
    }
    wrap.setAttribute('data-theme', isDarkMode() ? 'dark' : 'light');
    root.appendChild(wrap);

    if (data.error) {
      var err = el('div', 'qso-stats__error', data.fallbackText || '统计数据暂不可用，请稍后再试');
      err.appendChild(el('div', 'qso-stats__error-detail', data.error));
      wrap.appendChild(err);
      root.setAttribute('data-qso-stats-dash', '1');
      root.__qsStats = data;
      dashRoots = dashRoots.filter(function (r) { return r !== root; });
      dashRoots.push(root);
      return;
    }

    var stats = data.statistics || {};
    var layout = normalizeLayout(data.layout);

    // 1. 贯穿横幅（替换主题/页面标题，避免双重标题）
    wrap.appendChild(modernHeroEl(data.pageTitle || pageTitle()));

    // 2. 呼号查询：悬浮玻璃卡，压住横幅下缘
    if (data.searchEnabled && layoutHas(layout, 'search')) {
      var searchCard = el('section', 'qs-dash__search-card');
      wrap.appendChild(searchCard);
      searchSection(root, searchCard, Number(data.searchMaxResults) || 50);
    }

    // 3. 核心指标（Bento）
    if (layoutHas(layout, 'kpi')) {
      wrap.appendChild(modernSectionHead(
        'M3 3v18h18M7 14l4-4 3 3 5-6', '核心指标', '自动缓存 · 定时刷新'));
      wrap.appendChild(modernKpis(stats));
    }

    // 4. 图表面板
    if (layout.some(function (it) {
      return it.key === 'day' || it.key === 'month' || it.key === 'mode' ||
        it.key === 'band' || it.key === 'year' || it.key === 'recent';
    })) {
      wrap.appendChild(modernSectionHead(
        'M4 20V10M10 20V4M16 20v-7M22 20H2', '通联趋势与分布', '图表随主题深浅色自动重绘'));
      wrap.appendChild(modernPanels(layout, stats, data));
    }

    // 5. 页脚
    wrap.appendChild(modernFoot(data.updatedAt));

    root.setAttribute('data-qso-stats-dash', '1');
    root.__qsStats = data;
    dashRoots = dashRoots.filter(function (r) { return r !== root; });
    dashRoots.push(root);
    renderDashCharts(root, stats);
  }

  function renderDashboard(root, data) {
    // 数据展示默认主题：auto -> 跟随站点/系统；light/dark -> 强制
    if (data && data.defaultTheme === 'light') {
      qsThemeOverride = false;
    } else if (data && data.defaultTheme === 'dark') {
      qsThemeOverride = true;
    } else {
      qsThemeOverride = null;
    }
    var style = (data && data.displayStyle) === 'classic' ? 'classic' : 'modern';
    if (style === 'classic') {
      renderClassicDashboard(root, data);
    } else {
      renderModernDashboard(root, data);
    }
  }

  function renderClassicDashboard(root, data) {
    root.innerHTML = '';
    var wrap = el('div', 'qso-stats qso-stats--dash');

    if (data.error) {
      var err = el('div', 'qso-stats__error', data.fallbackText || '统计数据暂不可用，请稍后再试');
      err.appendChild(el('div', 'qso-stats__error-detail', data.error));
      wrap.appendChild(err);
      root.appendChild(wrap);
      return;
    }

    var stats = data.statistics || {};
    // 面板顺序由后台布局配置决定（默认：呼号查询 → KPI → 日/月 → 模式/频段 → 历年/最近）
    var layout = normalizeLayout(data.layout);
    var grid = el('div', 'qs-dash__grid qs-dash__grid--flow');

    layout.forEach(function (item) {
      var full = item.span === 2;
      var panelEl = null;
      switch (item.key) {
        case 'search':
          if (data.searchEnabled) {
            panelEl = el('div', 'qs-dash__slot' + (full ? ' qs-dash__slot--full' : ''));
            searchSection(root, panelEl, Number(data.searchMaxResults) || 50);
          }
          break;
        case 'kpi':
          panelEl = el('div', 'qs-dash__slot' + (full ? ' qs-dash__slot--full' : ''));
          panelEl.appendChild(kpisView(stats));
          break;
        case 'day':
          panelEl = dashPanel('近 30 日通联（日统计）', 'day', null, full);
          break;
        case 'month':
          panelEl = dashPanel('本年每月通联（月统计）', 'month', null, full);
          break;
        case 'year':
          panelEl = dashPanel('历年通联', 'year', null, full);
          break;
        case 'mode':
          panelEl = dashPanel('模式分布', 'mode', 'mode', full);
          break;
        case 'band':
          panelEl = dashPanel('频段分布', 'band', 'band', full);
          break;
        case 'recent':
          panelEl = dashPanel('最近通联', null, null, full);
          renderDashRecent(panelEl, data.recent || []);
          break;
      }
      if (panelEl) {
        grid.appendChild(panelEl);
      }
    });

    wrap.appendChild(grid);

    if (data.updatedAt) {
      var foot = el('div', 'qso-stats__footer');
      foot.appendChild(el('span', 'qso-stats__live', ''));
      foot.appendChild(el('span', 'qso-stats__footer-text',
        '更新于 ' + data.updatedAt.replace('T', ' ').slice(0, 16)));
      wrap.appendChild(foot);
    }

    root.appendChild(wrap);

    // 记录仪表盘数据，供主题深浅色切换后重绘图表
    root.setAttribute('data-qso-stats-dash', '1');
    root.__qsStats = data;
    dashRoots = dashRoots.filter(function (r) { return r !== root; });
    dashRoots.push(root);
    renderDashCharts(root, stats);
  }

  /* ---------------- 渲染入口 ---------------- */

  function render(root, data) {
    root.innerHTML = '';
    var wrap = el('div', 'qso-stats');

    // 独立统计页面（data-qso-stats-page="1"）时由主题/页面标题接管，
    // 不再渲染组件内嵌的区块标题，避免与页面标题重复（双重标题）。
    var isDedicatedPage = root.getAttribute('data-qso-stats-page') === '1';
    if (data.sectionTitle && data.showSectionTitle && !isDedicatedPage
        && !headingMatches(root, data.sectionTitle)) {
      wrap.appendChild(el('h2', 'qso-stats__header', data.sectionTitle));
    }

    if (data.error) {
      var err = el('div', 'qso-stats__error', data.fallbackText || '统计数据暂不可用，请稍后再试');
      err.appendChild(el('div', 'qso-stats__error-detail', data.error));
      wrap.appendChild(err);
    } else {
      if (data.searchEnabled) {
        searchSection(root, wrap, Number(data.searchMaxResults) || 50);
      }
      (data.sections || []).forEach(function (section) {
        wrap.appendChild(card(section));
      });
      if (!data.sections || !data.sections.length) {
        wrap.appendChild(el('div', 'qso-stats__error', '暂无可展示的统计项目，请到后台「插件设置」中启用。'));
      }
    }

    if (data.showUpdatedAt && data.updatedAt) {
      wrap.appendChild(el('div', 'qso-stats__footer', '更新于 ' + data.updatedAt.replace('T', ' ').slice(0, 16)));
    }

    root.appendChild(wrap);
  }

  function renderFailure(root, err) {
    root.innerHTML = '';
    var wrap = el('div', 'qso-stats');
    var errBox = el('div', 'qso-stats__error', '统计数据加载失败');
    errBox.appendChild(el('div', 'qso-stats__error-detail',
      (err && err.message) ? esc(err.message) : '接口请求异常'));
    wrap.appendChild(errBox);
    root.appendChild(wrap);
  }

  function initWidget(root) {
    if (root.getAttribute('data-qso-stats-init') === '1') {
      return;
    }
    root.setAttribute('data-qso-stats-init', '1');

    var isDedicatedPage = root.getAttribute('data-qso-stats-page') === '1';
    var endpoint = root.getAttribute(isDedicatedPage ? 'data-dashboard-endpoint' : 'data-endpoint')
      || (isDedicatedPage ? DASHBOARD_ENDPOINT : DEFAULT_ENDPOINT);
    var refresh = parseInt(root.getAttribute('data-refresh') || '0', 10);

    function load() {
      fetch(endpoint)
        .then(function (res) {
          if (!res.ok) {
            throw new Error('HTTP ' + res.status);
          }
          return res.json();
        })
        .then(function (data) {
          if (isDedicatedPage) {
            renderDashboard(root, data);
          } else {
            render(root, data);
          }
        })
        .catch(function (err) {
          renderFailure(root, err);
        });
    }

    load();
    if (isFinite(refresh) && refresh >= MIN_REFRESH) {
      setInterval(load, refresh * 1000);
    }
  }

  function boot() {
    var roots = document.querySelectorAll('.qso-stats-widget');
    for (var i = 0; i < roots.length; i++) {
      initWidget(roots[i]);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // 监听动态插入的组件容器（如主题异步加载的内容）
  if (window.MutationObserver) {
    var observer = new MutationObserver(function () {
      boot();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    // 主题深浅色切换时重绘图表
    var themeObserver = new MutationObserver(function () {
      // 已在后台「默认主题」强制指定亮 / 暗色时，忽略站点主题切换
      if (qsThemeOverride !== null) {
        return;
      }
      dashRoots.forEach(function (dashRoot) {
        if (!document.body.contains(dashRoot)) {
          return;
        }
        var modern = dashRoot.querySelector('.qs-dash--modern');
        if (modern) {
          modern.setAttribute('data-theme', isDarkMode() ? 'dark' : 'light');
        }
        var stats = dashRoot.__qsStats && dashRoot.__qsStats.statistics;
        if (!stats) {
          return; // 无统计数据时不重绘，避免把已渲染图表清成空白
        }
        dashRoot.querySelectorAll('[data-dash-chart]').forEach(function (chartEl) {
          var inst = window.echarts && window.echarts.getInstanceByDom
            ? window.echarts.getInstanceByDom(chartEl) : null;
          if (inst) {
            inst.dispose();
          }
          chartEl.innerHTML = '';
        });
        renderDashCharts(dashRoot, stats);
      });
    });
    themeObserver.observe(document.documentElement,
      { attributes: true, attributeFilter: ['class'] });
  }

  // 窗口尺寸变化时自适应图表
  if (window.addEventListener && window.echarts) {
    window.addEventListener('resize', function () {
      dashRoots.forEach(function (dashRoot) {
        if (!document.body.contains(dashRoot)) {
          return;
        }
        dashRoot.querySelectorAll('[data-dash-chart]').forEach(function (chartEl) {
          var inst = window.echarts.getInstanceByDom
            ? window.echarts.getInstanceByDom(chartEl) : null;
          if (inst) {
            inst.resize();
          }
        });
      });
    });
  }
})();