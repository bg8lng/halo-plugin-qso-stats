/* ============================================================
   通联统计组件（QsoStats plugin）
   - 自动初始化页面上所有 .qso-stats-widget 容器
   - 从 /qso-stats/api/statistics（可用 data-endpoint 覆盖）拉取数据渲染
   - data-refresh="秒" 可开启自动刷新（默认关闭）
   依赖：无。兼容性：ES5 语法，支持旧浏览器。
   ============================================================ */
(function () {
  'use strict';

  var DEFAULT_ENDPOINT = '/qso-stats/api/statistics';
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
      var meta = [row.band, row.mode].filter(Boolean).join(' / ');
      rowEl.appendChild(el('span', 'qso-stats__recent-meta', meta));
      rowEl.appendChild(el('span', 'qso-stats__recent-time', row.time || ''));
      wrap.appendChild(rowEl);
    });
    if (!rows || !rows.length) {
      wrap.appendChild(el('div', '', '暂无通联记录'));
    }
    return wrap;
  }

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
