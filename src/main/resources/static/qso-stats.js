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

  /* ---------------- 渲染入口 ---------------- */

  function render(root, data) {
    root.innerHTML = '';
    var wrap = el('div', 'qso-stats');

    if (data.sectionTitle && data.showSectionTitle) {
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

    var endpoint = root.getAttribute('data-endpoint') || DEFAULT_ENDPOINT;
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
          render(root, data);
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
  }
})();
