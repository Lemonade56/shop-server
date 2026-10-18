// ===== 全局状态 =====
let token = localStorage.getItem('admin_token') || '';
let currentPage = 'dashboard';
let orderStatusFilter = 'all';
let orderStatusLabels = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已完成', 4: '已取消' };
let statusLabelMap = { 0: ['badge-yellow','待支付'], 1: ['badge-blue','已支付'], 2: ['badge-blue','已发货'], 3: ['badge-green','已完成'], 4: ['badge-gray','已取消'] };

// ===== 侧边栏收缩 =====
function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('collapsed');
}

// ===== API =====
async function api(method, path, body = null) {
    const opts = { method, headers: { 'Authorization': `Bearer ${token}` } };
    if (body !== null && method !== 'GET') {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = typeof body === 'string' ? body : JSON.stringify(body);
    }
    if (method === 'GET' && body) {
        const q = Object.entries(body).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
        path += (path.includes('?') ? '&' : '?') + q;
    }
    const r = await fetch(`/admin/${path}`, opts);
    return r.json();
}

function toast(msg, type = '') {
    const el = document.getElementById('toast');
    el.textContent = msg;
    el.className = 'toast ' + type;
    el.style.display = 'flex';
    setTimeout(() => { el.style.display = 'none'; }, 2200);
}

// ===== 登录 =====
async function doLogin() {
    const username = document.getElementById('loginUsername').value;
    const password = document.getElementById('loginPassword').value;
    try {
        const r = await api('POST', 'login', { username, password });
        if (r.code === 200) {
            token = r.data.token;
            localStorage.setItem('admin_token', token);
            showMain();
        } else {
            document.getElementById('loginError').textContent = r.message || '登录失败';
        }
    } catch (e) {
        document.getElementById('loginError').textContent = '网络错误';
    }
}

function doLogout() {
    localStorage.removeItem('admin_token');
    token = '';
    document.getElementById('mainPage').style.display = 'none';
    document.getElementById('loginPage').style.display = 'flex';
}

function showMain() {
    document.getElementById('loginPage').style.display = 'none';
    document.getElementById('mainPage').style.display = 'flex';
    showPage('dashboard');
}

// ===== 页面切换 =====
function showPage(page) {
    currentPage = page;
    document.querySelectorAll('.page').forEach(p => p.style.display = 'none');
    document.getElementById('page-' + page).style.display = 'block';
    document.querySelectorAll('.nav-item').forEach(n => {
        n.classList.toggle('active', n.dataset.page === page);
    });
    // 更新面包屑
    const names = { dashboard: '仪表盘', goods: '商品管理', orders: '订单管理', users: '用户管理', coupons: '优惠券管理', categories: '分类管理', banners: '轮播图管理', keywords: '搜索关键词' };
    document.getElementById('breadcrumb').textContent = names[page] || page;
    // 加载页面数据
    const loaders = { dashboard: loadDashboard, goods: () => loadGoods(1), orders: () => loadOrders(1), users: () => loadUsers(1), coupons: () => loadCoupons(1), categories: loadCategories, banners: loadBanners, keywords: loadKeywords };
    loaders[page]?.();
}

// ===== 仪表盘 =====
let salesChart = null;
let orderStatusChart = null;

async function loadDashboard() {
    try {
        const r = await api('GET', 'dashboard/stats');
        if (r.code !== 200) return;
        const s = r.data;
        document.getElementById('statGoods').textContent = s.goodsCount;
        document.getElementById('statOrders').textContent = s.orderCount;
        document.getElementById('statSales').textContent = '¥' + (s.totalSales || 0).toLocaleString();
        document.getElementById('statUsers').textContent = s.userCount;
        document.getElementById('dashboardDate').textContent = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });

        // 销售趋势图
        renderSalesChart(s.sales7Days || []);
        // 订单状态环形图
        renderOrderStatusChart(s.orderStatusDist || []);
        // 热销商品
        renderTopGoods(s.topGoods || []);
        // 最近订单
        renderRecentOrders(s.recentOrders || []);
    } catch (e) { console.error(e); }
}

function renderSalesChart(days) {
    const ctx = document.getElementById('salesChart').getContext('2d');
    // 如果没数据，生成占位
    const labels = days.length ? days.map(d => d.date.slice(5)) : ['周一','周二','周三','周四','周五','周六','周日'];
    const data = days.length ? days.map(d => d.amount / 100) : [0,0,0,0,0,0,0];

    if (salesChart) salesChart.destroy();
    salesChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: '销售额（元）',
                data,
                fill: true,
                backgroundColor: 'rgba(37,99,235,0.08)',
                borderColor: '#2563eb',
                borderWidth: 2.5,
                tension: 0.35,
                pointRadius: 4,
                pointBackgroundColor: '#2563eb',
                pointBorderColor: '#fff',
                pointBorderWidth: 2,
                pointHoverRadius: 6
            }]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { display: false }, tooltip: { backgroundColor: '#0f172a', padding: 12, cornerRadius: 8 } },
            scales: {
                y: { beginAtZero: true, grid: { color: '#f1f5f9' }, ticks: { color: '#94a3b8' } },
                x: { grid: { display: false }, ticks: { color: '#94a3b8' } }
            }
        }
    });
}

function renderOrderStatusChart(statuses) {
    const ctx = document.getElementById('orderStatusChart').getContext('2d');
    if (orderStatusChart) orderStatusChart.destroy();
    const colors = ['#f59e0b','#2563eb','#8b5cf6','#16a34a','#94a3b8'];
    const labels = statuses.length ? statuses.map(s => orderStatusLabels[s.status]) : ['待支付','已支付','已发货','已完成','已取消'];
    const data = statuses.length ? statuses.map(s => s.count) : [0,0,0,0,0];

    orderStatusChart = new Chart(ctx, {
        type: 'doughnut',
        data: { labels, datasets: [{ data, backgroundColor: colors, borderWidth: 0, hoverOffset: 8 }] },
        options: {
            responsive: true, maintainAspectRatio: false, cutout: '65%',
            plugins: {
                legend: { position: 'bottom', labels: { padding: 16, boxWidth: 10, font: { size: 12 }, color: '#64748b' } },
                tooltip: { backgroundColor: '#0f172a', padding: 12, cornerRadius: 8 }
            }
        }
    });
}

function renderTopGoods(list) {
    const el = document.getElementById('topGoodsList');
    if (!list.length) { el.innerHTML = '<div style="padding:40px;text-align:center;color:#94a3b8;">暂无热销数据</div>'; return; }
    el.innerHTML = list.map((g, i) => `
        <div class="top-goods-item">
            <div class="top-rank r${i+1}">${i+1}</div>
            <img src="${g.pic}" alt="" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%2244%22 height=%2244%22><rect fill=%22%23e2e8f0%22 width=%2244%22 height=%2244%22/></svg>'">
            <div class="top-goods-info">
                <div class="top-goods-title">${g.title}</div>
                <div class="top-goods-sold">已售 ${g.sold}</div>
            </div>
        </div>
    `).join('');
}

function renderRecentOrders(list) {
    const el = document.getElementById('recentOrdersList');
    if (!list.length) { el.innerHTML = '<div style="padding:40px;text-align:center;color:#94a3b8;">暂无订单</div>'; return; }
    el.innerHTML = list.map(o => `
        <div class="recent-order-item">
            <span class="order-no">#${o.orderNo || o.id}</span>
            <span class="order-price">¥${(o.totalPrice || 0).toLocaleString()}</span>
        </div>
    `).join('');
}

// ===== 分页组件 =====
function renderPagination(container, page, totalPages, onChange) {
    if (totalPages <= 1) { container.innerHTML = ''; return; }
    let html = `<span class="page-info">共 ${totalPages} 页</span>`;
    html += `<button class="page-btn" onclick="(${onChange})(${page-1})" ${page<=1?'disabled':''}>‹</button>`;
    const pages = [];
    for (let i = Math.max(1, page-2); i <= Math.min(totalPages, page+2); i++) pages.push(i);
    if (pages[0] > 1) { html += `<button class="page-btn" onclick="(${onChange})(1)">1</button>`; if (pages[0] > 2) html += `<span class="page-info">...</span>`; }
    pages.forEach(p => html += `<button class="page-btn ${p===page?'active':''}" onclick="(${onChange})(${p})">${p}</button>`);
    if (pages[pages.length-1] < totalPages) { if (pages[pages.length-1] < totalPages-1) html += `<span class="page-info">...</span>`; html += `<button class="page-btn" onclick="(${onChange})(${totalPages})">${totalPages}</button>`; }
    html += `<button class="page-btn" onclick="(${onChange})(${page+1})" ${page>=totalPages?'disabled':''}>›</button>`;
    container.innerHTML = html;
}

// ===== 商品列表 =====
async function loadGoods(page = 1) {
    const keyword = document.getElementById('goodsKeyword')?.value || '';
    try {
        const r = await api('GET', 'goods/list', { keyword, page });
        if (r.code !== 200) return;
        const tbody = document.getElementById('goodsTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(g => `
            <tr>
                <td>${g.id}</td>
                <td><img src="${g.mainPic}" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22><rect fill=%22%23e2e8f0%22 width=%2248%22 height=%2248%22/></svg>'"></td>
                <td>${g.title}</td>
                <td>¥${(g.price/100).toFixed(2)}</td>
                <td>${g.sold}</td>
                <td><span class="badge ${g.status===1?'badge-green':'badge-gray'}">${g.status===1?'上架':'下架'}</span></td>
                <td>
                    <button class="btn btn-sm" onclick="openGoodsModal(${g.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteGoods(${g.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="7" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('goodsPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadGoods');
    } catch (e) { console.error(e); }
}

// ===== 订单列表 =====
async function loadOrders(page = 1) {
    try {
        const body = { page };
        if (orderStatusFilter !== 'all') body.status = [orderStatusFilter];
        const r = await api('GET', 'order/list', body);
        if (r.code !== 200) return;
        const tbody = document.getElementById('ordersTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(o => {
            const [cls, label] = statusLabelMap[o.status] || ['badge-gray','未知'];
            return `<tr>
                <td style="font-family:monospace;font-size:13px;">${o.orderNo || o.id}</td>
                <td>${o.userId || '-'}</td>
                <td style="font-weight:600;">¥${(o.totalPrice || 0).toLocaleString()}</td>
                <td><span class="badge ${cls}">${label}</span></td>
                <td style="color:#64748b;font-size:13px;">${o.createTime || '-'}</td>
                <td><button class="btn btn-sm" onclick="viewOrder(${o.id})">查看</button></td>
            </tr>`;
        }).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('ordersPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadOrders');
    } catch (e) { console.error(e); }
}

function filterOrders(status) {
    orderStatusFilter = status;
    document.querySelectorAll('.status-tab').forEach(t => {
        t.classList.toggle('active', t.dataset.status === String(status));
    });
    loadOrders(1);
}

// ===== 用户列表 =====
async function loadUsers(page = 1) {
    try {
        const r = await api('GET', 'user/list', { page });
        if (r.code !== 200) return;
        const tbody = document.getElementById('usersTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(u => `
            <tr>
                <td>${u.id}</td>
                <td>${u.phone}</td>
                <td>${u.nickname || '-'}</td>
                <td><span class="badge ${u.status===1?'badge-green':'badge-gray'}">${u.status===1?'正常':'禁用'}</span></td>
                <td style="color:#64748b;font-size:13px;">${u.createTime || '-'}</td>
                <td>
                    <button class="btn btn-sm btn-danger" onclick="toggleUser(${u.id}, ${u.status===1?0:1})">${u.status===1?'禁用':'启用'}</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('usersPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadUsers');
    } catch (e) { console.error(e); }
}

// ===== 优惠券列表 =====
async function loadCoupons(page = 1) {
    try {
        const r = await api('GET', 'coupon/list', { page });
        if (r.code !== 200) return;
        const tbody = document.getElementById('couponsTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(c => `
            <tr>
                <td>${c.id}</td>
                <td>${c.name}</td>
                <td>-¥${(c.discount/100).toFixed(2)}</td>
                <td>满 ¥${(c.minAmount/100).toFixed(2)}</td>
                <td>${c.remainCount}/${c.totalCount}</td>
                <td style="color:#64748b;font-size:13px;">${c.startTime || '-'} ~ ${c.endTime || '-'}</td>
                <td><span class="badge ${c.status===1?'badge-green':'badge-gray'}">${c.status===1?'启用':'禁用'}</span></td>
            </tr>
        `).join('') || '<tr><td colspan="7" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('couponsPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadCoupons');
    } catch (e) { console.error(e); }
}

// ===== 分类 =====
async function loadCategories() {
    try {
        const r = await api('GET', 'category/list');
        if (r.code !== 200) return;
        const tbody = document.getElementById('categoriesTableBody');
        tbody.innerHTML = r.data.map(c => `
            <tr>
                <td>${c.id}</td>
                <td>${c.name}</td>
                <td>${c.parentId === 0 ? '—' : c.parentId}</td>
                <td>${c.sortNum}</td>
                <td>
                    <button class="btn btn-sm" onclick="openCategoryModal(${c.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteCategory(${c.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="5" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 轮播图 =====
async function loadBanners() {
    try {
        const r = await api('GET', 'banner/list');
        if (r.code !== 200) return;
        const tbody = document.getElementById('bannersTableBody');
        tbody.innerHTML = r.data.map(b => `
            <tr>
                <td>${b.id}</td>
                <td><img src="${b.pic}" style="width:120px;height:56px;object-fit:cover;" onerror="this.style.display='none'"></td>
                <td>${b.title || '-'}</td>
                <td>${b.sortNum}</td>
                <td><span class="badge ${b.status===1?'badge-green':'badge-gray'}">${b.status===1?'启用':'禁用'}</span></td>
                <td>
                    <button class="btn btn-sm" onclick="toggleBanner(${b.id}, ${b.status===1?0:1})">${b.status===1?'禁用':'启用'}</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteBanner(${b.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 关键词 =====
async function loadKeywords() {
    try {
        const r = await api('GET', 'keyword/list');
        if (r.code !== 200) return;
        const tbody = document.getElementById('keywordsTableBody');
        tbody.innerHTML = r.data.map(k => `
            <tr>
                <td>${k.id}</td>
                <td>${k.keyword}</td>
                <td>${k.searchCount}</td>
                <td><span class="badge ${k.isHot?'badge-yellow':'badge-gray'}">${k.isHot?'热门':'普通'}</span></td>
                <td>${k.sortNum}</td>
                <td>
                    <button class="btn btn-sm" onclick="openKeywordModal(${k.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteKeyword(${k.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 通用：打开/关闭弹窗 =====
function closeModal(id) { document.getElementById(id).style.display = 'none'; }
function openGoodsModal(id = null) {
    document.getElementById('goodsModalTitle').textContent = id ? '编辑商品' : '新增商品';
    if (id) {
        // 简化：直接填表单，实际可加 GET 详情
        document.getElementById('goods_typeId').value = 1;
        document.getElementById('goods_price').value = '';
        document.getElementById('goods_title').value = '';
        document.getElementById('goods_mainPic').value = '';
    }
    document.getElementById('goodsModal').style.display = 'flex';
}
function openCategoryModal(id = null) {
    document.getElementById('categoryModalTitle').textContent = id ? '编辑分类' : '新增分类';
    document.getElementById('categoryModal').style.display = 'flex';
}
function openBannerModal() { document.getElementById('bannerModal').style.display = 'flex'; }
function openKeywordModal() { document.getElementById('keywordModal').style.display = 'flex'; }
function openCouponModal() { document.getElementById('couponModal').style.display = 'flex'; }

// ===== 通用保存 =====
async function saveCategory() {
    const body = {
        name: document.getElementById('category_name').value,
        parentId: parseInt(document.getElementById('category_parentId').value),
        pic: document.getElementById('category_pic').value,
        sortNum: parseInt(document.getElementById('category_sortNum').value)
    };
    const r = await api('POST', 'category/add', body);
    if (r.code === 200) { closeModal('categoryModal'); toast('新增分类成功'); loadCategories(); }
    else toast(r.message || '操作失败', 'error');
}
async function saveBanner() {
    const body = {
        pic: document.getElementById('banner_pic').value,
        url: document.getElementById('banner_url').value,
        title: document.getElementById('banner_title').value,
        sortNum: parseInt(document.getElementById('banner_sortNum').value),
        status: parseInt(document.getElementById('banner_status').value)
    };
    const r = await api('POST', 'banner/add', body);
    if (r.code === 200) { closeModal('bannerModal'); toast('新增轮播图成功'); loadBanners(); }
    else toast(r.message || '操作失败', 'error');
}
async function saveKeyword() {
    const body = {
        keyword: document.getElementById('keyword_keyword').value,
        sortNum: parseInt(document.getElementById('keyword_sortNum').value),
        isHot: document.getElementById('keyword_isHot').checked
    };
    const r = await api('POST', 'keyword/add', body);
    if (r.code === 200) { closeModal('keywordModal'); toast('新增关键词成功'); loadKeywords(); }
    else toast(r.message || '操作失败', 'error');
}
async function saveGoods() {
    const body = {
        typeId: parseInt(document.getElementById('goods_typeId').value),
        price: parseInt(document.getElementById('goods_price').value),
        title: document.getElementById('goods_title').value,
        subTitle: document.getElementById('goods_subTitle').value,
        mainPic: document.getElementById('goods_mainPic').value,
        status: parseInt(document.getElementById('goods_status').value),
        sortNum: parseInt(document.getElementById('goods_sortNum').value)
    };
    const r = await api('POST', 'goods/add', body);
    if (r.code === 200) { closeModal('goodsModal'); toast('新增商品成功'); loadGoods(1); }
    else toast(r.message || '操作失败', 'error');
}
async function saveCoupon() {
    const body = {
        name: document.getElementById('coupon_name').value,
        discount: parseInt(document.getElementById('coupon_discount').value),
        minAmount: parseInt(document.getElementById('coupon_minAmount').value),
        startTime: document.getElementById('coupon_startTime').value,
        endTime: document.getElementById('coupon_endTime').value,
        totalCount: parseInt(document.getElementById('coupon_totalCount').value)
    };
    const r = await api('POST', 'coupon/add', body);
    if (r.code === 200) { closeModal('couponModal'); toast('新增优惠券成功'); loadCoupons(1); }
    else toast(r.message || '操作失败', 'error');
}

// ===== 通用操作 =====
async function deleteGoods(id) {
    if (!confirm('确定删除该商品？')) return;
    const r = await api('POST', 'goods/delete/' + id, {});
    toast(r.code === 200 ? '删除商品成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadGoods(1);
}
async function deleteCategory(id) {
    if (!confirm('确定删除该分类？')) return;
    const r = await api('POST', 'category/delete/' + id, {});
    toast(r.code === 200 ? '删除分类成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadCategories();
}
async function deleteBanner(id) {
    if (!confirm('确定删除该轮播图？')) return;
    const r = await api('POST', 'banner/delete/' + id, {});
    toast(r.code === 200 ? '删除轮播图成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadBanners();
}
async function deleteKeyword(id) {
    if (!confirm('确定删除该关键词？')) return;
    const r = await api('POST', 'keyword/delete/' + id, {});
    toast(r.code === 200 ? '删除关键词成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadKeywords();
}
async function toggleBanner(id, status) {
    const r = await api('POST', 'banner/status/' + id + '/' + status, {});
    if (r.code === 200) { toast(status === 1 ? '启用轮播图成功' : '禁用轮播图成功', 'success'); loadBanners(); }
    else toast(r.message || '操作失败', 'error');
}
async function toggleUser(id, status) {
    const r = await api('POST', 'user/status/' + id + '/' + status, {});
    if (r.code === 200) { toast(status === 1 ? '启用用户成功' : '禁用用户成功', 'success'); loadUsers(1); }
    else toast(r.message || '操作失败', 'error');
}
function viewOrder(id) {
    toast('订单详情功能待开发');
}

// ===== 启动 =====
if (token) {
    api('GET', 'dashboard/stats').then(r => {
        if (r.code === 200) showMain();
        else { localStorage.removeItem('admin_token'); location.reload(); }
    }).catch(() => { localStorage.removeItem('admin_token'); });
}
