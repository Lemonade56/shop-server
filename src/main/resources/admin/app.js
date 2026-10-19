// ===== 全局状态 =====
let token = localStorage.getItem('admin_token') || '';
let currentPage = 'dashboard';
let orderStatusFilter = 'all';
let editingUserId = null;
let editingCouponId = null;
let editingKeywordId = null;
let editingGoodsId = null;
let currentOrderDetailId = null;
let orderStatusLabels = { 0: '待支付', 1: '已支付', 2: '已发货', 3: '已签收', 4: '已取消' };
let statusLabelMap = { 0: ['badge-yellow','待支付'], 1: ['badge-blue','已支付'], 2: ['badge-blue','已发货'], 3: ['badge-green','已签收'], 4: ['badge-gray','已取消'] };

// ===== 侧边栏收缩 =====
function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('collapsed');
}

// ===== API 工具 =====
async function api(method, path, body = null) {
    const opts = { method, headers: { 'Authorization': `Bearer ${token}` } };
    if (body !== null && method !== 'GET') {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = typeof body === 'string' ? body : JSON.stringify(body);
    }
    if (method === 'GET' && body) {
        const q = Object.entries(body).filter(([,v]) => v !== undefined && v !== null && v !== '')
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
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
            document.getElementById('adminName').textContent = r.data.username || 'admin';
            showMain();
        } else {
            document.getElementById('loginError').textContent = r.message || '登录失败';
        }
    } catch (e) { document.getElementById('loginError').textContent = '网络错误'; }
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
    document.querySelectorAll('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.page === page));
    const names = {
        dashboard: '数据概览', goods: '食物管理', categories: '分类管理',
        orders: '订单管理', coupons: '优惠券管理', users: '用户管理',
        banners: '轮播图管理', keywords: '搜索关键词'
    };
    document.getElementById('breadcrumb').textContent = names[page] || page;
    const loaders = {
        dashboard: loadDashboard,
        goods: () => loadGoods(1),
        categories: loadCategories,
        orders: () => loadOrders(1),
        coupons: loadCoupons,
        users: () => loadUsers(1),
        banners: loadBanners,
        keywords: loadKeywords
    };
    loaders[page]?.();
}

// ===== 仪表盘 =====
let salesChart = null, orderStatusChart = null;

async function loadDashboard() {
    try {
        const r = await api('GET', 'dashboard/stats');
        if (r.code !== 200) return;
        const s = r.data;
        document.getElementById('statGoods').textContent = s.goodsCount;
        document.getElementById('statOrders').textContent = s.orderCount;
        document.getElementById('statSales').textContent = '¥' + ((s.totalSales || 0) / 100).toLocaleString();
        document.getElementById('statUsers').textContent = s.userCount;
        document.getElementById('dashboardDate').textContent = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
        renderSalesChart(s.sales7Days || []);
        renderOrderStatusChart(s.orderStatusDist || []);
        renderTopGoods(s.topGoods || []);
        renderRecentOrders(s.recentOrders || []);
    } catch (e) { console.error(e); }
}

function renderSalesChart(days) {
    const ctx = document.getElementById('salesChart').getContext('2d');
    const labels = days.length ? days.map(d => d.date.slice(5)) : ['周一','周二','周三','周四','周五','周六','周日'];
    const data = days.length ? days.map(d => d.amount / 100) : [0,0,0,0,0,0,0];
    if (salesChart) salesChart.destroy();
    salesChart = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets: [{
            label: '销售额（元）', data, fill: true,
            backgroundColor: 'rgba(37,99,235,0.08)', borderColor: '#2563eb', borderWidth: 2.5,
            tension: 0.35, pointRadius: 4, pointBackgroundColor: '#2563eb',
            pointBorderColor: '#fff', pointBorderWidth: 2, pointHoverRadius: 6
        }]},
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { display: false }, tooltip: { backgroundColor: '#1e293b', padding: 12, cornerRadius: 8 } },
            scales: { y: { beginAtZero: true, grid: { color: '#f1f5f9' }, ticks: { color: '#94a3b8' } }, x: { grid: { display: false }, ticks: { color: '#94a3b8' } } }
        }
    });
}

function renderOrderStatusChart(statuses) {
    const ctx = document.getElementById('orderStatusChart').getContext('2d');
    if (orderStatusChart) orderStatusChart.destroy();
    const colors = ['#f59e0b','#2563eb','#0ea5e9','#16a34a','#94a3b8'];
    const labels = statuses.length ? statuses.map(s => s.label) : ['待支付','已支付','已发货','已完成','已取消'];
    const data = statuses.length ? statuses.map(s => s.count) : [0,0,0,0,0];
    orderStatusChart = new Chart(ctx, {
        type: 'doughnut',
        data: { labels, datasets: [{ data, backgroundColor: colors, borderWidth: 0, hoverOffset: 8 }] },
        options: { responsive: true, maintainAspectRatio: false, cutout: '65%',
            plugins: { legend: { position: 'bottom', labels: { padding: 16, boxWidth: 10, font: { size: 12 }, color: '#64748b' } }, tooltip: { backgroundColor: '#1e293b', padding: 12, cornerRadius: 8 } } }
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
            <span class="order-price">¥${(o.totalPrice / 100).toLocaleString()}</span>
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

// ===== 食物列表（带编辑按钮）=====
async function loadGoods(page = 1) {
    const keyword = document.getElementById('goodsKeyword')?.value || '';
    try {
        const r = await api('GET', 'foods/list', { keyword, page });
        if (r.code !== 200) return;
        const tbody = document.getElementById('goodsTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(g => `
            <tr>
                <td>${g.id}</td>
                <td><img src="${g.mainPic}" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%2248%22 height=%2248%22><rect fill=%22%23e2e8f0%22 width=%2248%22 height=%2248%22/></svg>'"></td>
                <td>${g.title}</td>
                <td style="color:#2563eb;font-weight:600;">¥${(g.price/100).toFixed(2)}</td>
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

// ===== 分类列表 =====
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
                    <button class="btn btn-sm" onclick="openCategoryModal(${c.id}, ${JSON.stringify(c.name)}, ${c.parentId}, ${JSON.stringify(c.pic || '')}, ${c.sortNum})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteCategory(${c.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="5" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 订单列表（带详情查看）=====
async function loadOrders(page = 1) {
    try {
        const params = { page };
        if (orderStatusFilter !== 'all') params.status = orderStatusFilter;
        const r = await api('GET', 'order/list', params);
        if (r.code !== 200) return;
        const tbody = document.getElementById('ordersTableBody');
        const list = r.data.list || [];
        tbody.innerHTML = list.map(o => {
            const [cls, label] = statusLabelMap[o.status] || ['badge-gray','未知'];
            return `<tr>
                <td style="font-family:monospace;font-size:13px;">${o.orderNo || o.id}</td>
                <td>${o.userId || '-'}</td>
                <td style="color:#2563eb;font-weight:600;">¥${(o.totalPrice / 100).toLocaleString()}</td>
                <td><span class="badge ${cls}">${label}</span></td>
                <td style="color:#64748b;font-size:13px;">${o.createTime || '-'}</td>
                <td>
                    <button class="btn btn-sm" onclick="viewOrder(${o.id})">详情</button>
                    ${o.status === 1 ? `<button class="btn btn-sm btn-primary" onclick="shipOrder(${o.id})">发货</button>` : ''}
                </td>
            </tr>`;
        }).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('ordersPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadOrders');
    } catch (e) { console.error(e); }
}

function filterOrders(status) {
    orderStatusFilter = status;
    document.querySelectorAll('.status-tab').forEach(t => t.classList.toggle('active', t.dataset.status === String(status)));
    loadOrders(1);
}

async function shipOrder(id) {
    if (!confirm('确定将该订单标记为已发货？')) return;
    const r = await api('POST', 'order/ship', { orderId: id });
    toast(r.code === 200 ? '发货成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadOrders(1);
}

// 订单详情弹窗
async function viewOrder(id) {
    currentOrderDetailId = id;
    try {
        const r = await api('GET', 'order/detail', { id });
        if (r.code !== 200) { toast(r.message || '获取订单详情失败', 'error'); return; }
        const d = r.data;
        const [cls, label] = statusLabelMap[d.status] || ['badge-gray','未知'];
        const canShip = d.status === 1;
        document.getElementById('orderShipBtn').style.display = canShip ? 'inline-flex' : 'none';
        document.getElementById('orderDetailContent').innerHTML = `
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px;">
                <div><b>订单号：</b>${d.orderNo || d.id}</div>
                <div><b>状态：</b><span class="badge ${cls}">${label}</span></div>
                <div><b>用户ID：</b>${d.userId}</div>
                <div><b>创建时间：</b>${d.createTime || '-'}</div>
                <div><b>支付时间：</b>${d.payTime || '-'}</div>
                <div><b>发货时间：</b>${d.shipTime || '-'}</div>
            </div>
            <div style="background:#f8fafc;border-radius:10px;padding:14px;margin-bottom:16px;">
                <div style="font-size:12px;color:#64748b;margin-bottom:8px;">收货地址</div>
                <div style="font-size:14px;">${d.address || '-'}</div>
            </div>
            <div style="background:#f8fafc;border-radius:10px;padding:14px;margin-bottom:16px;">
                <div style="font-size:12px;color:#64748b;margin-bottom:8px;">备注</div>
                <div style="font-size:14px;">${d.remark || '（无）'}</div>
            </div>
            <div style="margin-bottom:12px;"><b>食物列表</b></div>
            <div style="border:1px solid #e2e8f0;border-radius:10px;overflow:hidden;">
                <table style="width:100%;border-collapse:collapse;font-size:13px;">
                    <thead><tr style="background:#f8fafc;color:#64748b;text-align:left;">
                        <th style="padding:10px;">食物</th><th style="padding:10px;">规格</th>
                        <th style="padding:10px;">单价</th><th style="padding:10px;">数量</th><th style="padding:10px;">小计</th>
                    </tr></thead>
                    <tbody>${d.goodsList.map(g => `
                        <tr style="border-top:1px solid #f1f5f9;">
                            <td style="padding:10px;display:flex;gap:8px;align-items:center;">
                                <img src="${g.mainPic}" style="width:40px;height:40px;border-radius:6px;object-fit:cover;" onerror="this.style.display='none'">
                                <span>${g.title}</span>
                            </td>
                            <td style="padding:10px;color:#64748b;">${g.specInfo || '-'}</td>
                            <td style="padding:10px;">¥${(g.price/100).toFixed(2)}</td>
                            <td style="padding:10px;">×${g.quantity}</td>
                            <td style="padding:10px;font-weight:600;color:#2563eb;">¥${((g.price * g.quantity)/100).toFixed(2)}</td>
                        </tr>
                    `).join('')}</tbody>
                </table>
            </div>
            <div style="text-align:right;margin-top:14px;padding-top:12px;border-top:1px solid #e2e8f0;">
                <span style="color:#64748b;">订单总额：</span>
                <span style="font-size:20px;font-weight:700;color:#2563eb;">¥${(d.totalPrice/100).toFixed(2)}</span>
            </div>
        `;
        document.getElementById('orderDetailModal').style.display = 'flex';
    } catch (e) { toast('获取订单详情失败', 'error'); console.error(e); }
}

async function shipOrderFromDetail() {
    if (!currentOrderDetailId) return;
    const r = await api('POST', 'order/ship', { orderId: currentOrderDetailId });
    toast(r.code === 200 ? '发货成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) { closeModal('orderDetailModal'); viewOrder(currentOrderDetailId); loadOrders(1); }
}

// ===== 用户列表（带编辑）=====
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
                    <button class="btn btn-sm" onclick="openUserModal(${u.id}, '${(u.nickname || '').replace(/'/g, "&apos;")}')">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="toggleUser(${u.id}, ${u.status===1?0:1})">${u.status===1?'禁用':'启用'}</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
        renderPagination(document.getElementById('usersPagination'), page, Math.max(1, Math.ceil(r.data.total / 10)), 'loadUsers');
    } catch (e) { console.error(e); }
}

// 用户编辑弹窗
function openUserModal(id, nickname) {
    editingUserId = id;
    document.getElementById('userModalTitle').textContent = `编辑用户 #${id}`;
    document.getElementById('user_id').value = id;
    document.getElementById('user_nickname').value = nickname || '';
    document.getElementById('user_password').value = '';
    document.getElementById('userModal').style.display = 'flex';
}

async function saveUser() {
    const nickname = document.getElementById('user_nickname').value;
    const password = document.getElementById('user_password').value;
    if (!editingUserId) return;
    const body = { userId: editingUserId, nickname };
    if (password.trim()) body.password = password.trim();
    const r = await api('PUT', 'user/edit', body);
    toast(r.code === 200 ? '编辑用户成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) { closeModal('userModal'); loadUsers(1); }
}

async function toggleUser(id, status) {
    const label = status === 1 ? '启用' : '禁用';
    if (!confirm(`确定${label}该用户？`)) return;
    const r = await api('POST', 'user/updateStatus', { userId: id, status });
    toast(r.code === 200 ? `${label}用户成功` : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadUsers(1);
}

// ===== 优惠券列表（带编辑）=====
async function loadCoupons() {
    try {
        const r = await api('GET', 'coupon/list');
        if (r.code !== 200) return;
        const tbody = document.getElementById('couponsTableBody');
        const list = r.data || [];
        tbody.innerHTML = list.map(c => `
            <tr>
                <td>${c.id}</td>
                <td>${c.name}</td>
                <td style="color:#2563eb;font-weight:600;">-¥${(c.discount/100).toFixed(2)}</td>
                <td>满 ¥${(c.minAmount/100).toFixed(2)}</td>
                <td>${c.remainCount}${c.totalCount ? '/' + c.totalCount : ''}</td>
                <td style="color:#64748b;font-size:13px;">${c.startTime || '-'} ~ ${c.endTime || '-'}</td>
                <td><span class="badge ${c.status===1?'badge-green':'badge-gray'}">${c.status===1?'启用':'禁用'}</span></td>
                <td>
                    <button class="btn btn-sm" onclick="openCouponModal(${c.id}, ${JSON.stringify(c.name)}, ${c.discount}, ${c.minAmount}, ${JSON.stringify(c.startTime || '')}, ${JSON.stringify(c.endTime || '')}, ${c.totalCount || 1000})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteCoupon(${c.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="8" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
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
                    <button class="btn btn-sm" onclick="openBannerModal(${b.id}, ${JSON.stringify(b.pic)}, ${JSON.stringify(b.url || '')}, ${JSON.stringify(b.title || '')}, ${b.sortNum}, ${b.status})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteBanner(${b.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 关键词（带编辑）=====
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
                <td><span class="badge ${k.isHot?'badge-yellow':'badge-gray'}">${k.isHot?'🔥 热门':'普通'}</span></td>
                <td>${k.sortNum}</td>
                <td>
                    <button class="btn btn-sm" onclick="openKeywordModal(${k.id}, '${k.keyword}', ${k.sortNum}, ${k.isHot})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteKeyword(${k.id})">删除</button>
                </td>
            </tr>
        `).join('') || '<tr><td colspan="6" style="text-align:center;padding:40px;color:#94a3b8;">暂无数据</td></tr>';
    } catch (e) { console.error(e); }
}

// ===== 弹窗控制 =====
function closeModal(id) { document.getElementById(id).style.display = 'none'; }

function openGoodsModal(id = null) {
    editingGoodsId = id;
    document.getElementById('goodsModalTitle').textContent = id ? '编辑食物' : '新增食物';
    // 先清空表单和预览
    document.getElementById('goods_typeId').value = 1;
    document.getElementById('goods_price').value = '';
    document.getElementById('goods_title').value = '';
    document.getElementById('goods_subTitle').value = '';
    document.getElementById('goods_mainPic').value = '';
    document.getElementById('goods_status').value = 1;
    document.getElementById('goods_sortNum').value = 0;
    document.getElementById('goodsPicPreview').innerHTML = '';
    document.getElementById('goodsPicFile').value = '';
    // 新增模式规格区域隐藏（没有 goodsId）
    document.getElementById('goodsSpecSection').style.display = 'none';
    document.getElementById('goodsModal').style.display = 'flex';

    // 编辑模式：从后端拉详情并回显
    if (id) {
        api('GET', 'foods/detail', { id }).then(r => {
            if (r.code === 200 && r.data) {
                const d = r.data;
                document.getElementById('goods_typeId').value = d.typeId || 1;
                document.getElementById('goods_price').value = d.price || 0;
                document.getElementById('goods_title').value = d.title || '';
                document.getElementById('goods_subTitle').value = d.subTitle || '';
                document.getElementById('goods_mainPic').value = d.mainPic || '';
                document.getElementById('goods_status').value = d.status ?? 1;
                document.getElementById('goods_sortNum').value = d.sortNum || 0;
                // 显示当前图片预览
                if (d.mainPic) {
                    document.getElementById('goodsPicPreview').innerHTML =
                        `<img src="${d.mainPic}" style="width:80px;height:80px;object-fit:cover;border-radius:8px;border:1px solid #e2e8f0;" onerror="this.style.display='none'">`;
                }
                // 加载该商品的规格列表
                loadGoodsSpecs(id);
            } else {
                toast(r.message || '获取食物详情失败', 'error');
            }
        }).catch(() => toast('网络错误', 'error'));
    }
}

// ===== 本地图片上传（base64 DataURL 方案）=====
// 选择文件后用 FileReader 读成 data:image/xxx;base64,xxx，POST 给服务器解码存本地
async function uploadGoodsPic(input) {
    const file = input.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) { toast('请选择图片文件', 'error'); return; }
    if (file.size > 5 * 1024 * 1024) { toast('图片不能超过 5MB', 'error'); return; }

    const preview = document.getElementById('goodsPicPreview');
    preview.innerHTML = '<span style="color:#64748b;font-size:13px;">读取中...</span>';

    // 用 FileReader 读成 DataURL
    const dataUrl = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });

    preview.innerHTML = '<span style="color:#64748b;font-size:13px;">上传中...</span>';

    try {
        const r = await api('POST', 'upload/image', { dataUrl });
        if (r.code === 200) {
            document.getElementById('goods_mainPic').value = r.data.url;
            preview.innerHTML = `<img src="${r.data.url}" style="width:80px;height:80px;object-fit:cover;border-radius:8px;border:1px solid #e2e8f0;">`;
            toast('图片上传成功', 'success');
        } else {
            preview.innerHTML = `<img src="${dataUrl}" style="width:80px;height:80px;object-fit:cover;border-radius:8px;border:1px solid #e2e8f0;">`;
            toast(r.message || '上传失败', 'error');
        }
    } catch (e) {
        preview.innerHTML = `<img src="${dataUrl}" style="width:80px;height:80px;object-fit:cover;border-radius:8px;border:1px solid #e2e8f0;">`;
        toast('上传失败', 'error');
    }
}

// ===== 商品规格管理（在食物编辑弹窗内）=====
// 打开规格管理区域：编辑模式直接从后端拉，新增模式不显示
async function loadGoodsSpecs(goodsId) {
    const section = document.getElementById('goodsSpecSection');
    section.style.display = 'block';
    const listEl = document.getElementById('goodsSpecList');
    listEl.innerHTML = '<span style="color:#64748b;font-size:13px;">加载中...</span>';

    try {
        const r = await api('GET', 'foods/spec/list', { foodsId: goodsId });
        if (r.code === 200 && r.data.length) {
            listEl.innerHTML = r.data.map(spec => renderSpecRow(spec)).join('');
        } else {
            listEl.innerHTML = '<span style="color:#94a3b8;font-size:13px;">暂无规格，点右上角"新增规格"添加</span>';
        }
    } catch (e) {
        listEl.innerHTML = '<span style="color:#ef4444;font-size:13px;">加载失败</span>';
    }
}

// 渲染一条规格的 HTML（可编辑行）
function renderSpecRow(spec) {
    return `
        <div class="spec-row" data-spec-id="${spec.id}" style="display:flex;gap:8px;align-items:center;margin-bottom:8px;padding:8px;background:#f8fafc;border-radius:8px;">
            <input type="text" value="${spec.spec.replace(/"/g, '&quot;')}" style="flex:2;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="如 原味/104g*9罐">
            <input type="number" value="${spec.price}" style="width:90px;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="价格(分)">
            <input type="number" value="${spec.stock}" style="width:80px;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="库存">
            <button class="btn btn-sm btn-primary" onclick="saveSpecRow(${spec.id})">保存</button>
            <button class="btn btn-sm btn-danger" onclick="deleteSpecRow(${spec.id})">删</button>
        </div>
    `;
}

// 新增规格（前端先加空行，用户填完点"保存"再真正添加）
function addSpecRow() {
    const listEl = document.getElementById('goodsSpecList');
    // 先加一个临时行，等用户点保存时真正调 API
    const tempId = 'new_' + Date.now();
    const html = `
        <div class="spec-row" data-spec-id="${tempId}" style="display:flex;gap:8px;align-items:center;margin-bottom:8px;padding:8px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;">
            <input type="text" style="flex:2;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="如 原味/104g*9罐">
            <input type="number" value="" style="width:90px;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="价格(分)">
            <input type="number" value="0" style="width:80px;padding:6px 8px;border:1px solid #e2e8f0;border-radius:6px;font-size:13px;" placeholder="库存">
            <button class="btn btn-sm btn-primary" onclick="saveNewSpec(this)">保存</button>
            <button class="btn btn-sm btn-danger" onclick="this.closest('.spec-row').remove()">取消</button>
        </div>
    `;
    listEl.insertAdjacentHTML('beforeend', html);
}

// 保存新增的规格（调 POST foods/spec/add）
async function saveNewSpec(btn) {
    const row = btn.closest('.spec-row');
    const inputs = row.querySelectorAll('input');
    const spec = inputs[0].value.trim();
    const price = parseInt(inputs[1].value) || 0;
    const stock = parseInt(inputs[2].value) || 0;
    if (!spec) { toast('规格描述不能为空', 'error'); return; }
    const r = await api('POST', 'foods/spec/add', { foodsId: editingGoodsId, spec, price, stock });
    toast(r.code === 200 ? '新增规格成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadGoodsSpecs(editingGoodsId);
}

// 保存已有规格的修改（调 PUT foods/spec/update）
async function saveSpecRow(specId) {
    const row = document.querySelector(`.spec-row[data-spec-id="${specId}"]`);
    const inputs = row.querySelectorAll('input');
    const spec = inputs[0].value.trim();
    const price = parseInt(inputs[1].value) || 0;
    const stock = parseInt(inputs[2].value) || 0;
    if (!spec) { toast('规格描述不能为空', 'error'); return; }
    const r = await api('PUT', 'foods/spec/update', { id: specId, spec, price, stock });
    toast(r.code === 200 ? '修改规格成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
}

// 删除规格
async function deleteSpecRow(specId) {
    if (!confirm('确定删除该规格？')) return;
    const r = await api('DELETE', 'foods/spec/delete', { id: specId });
    toast(r.code === 200 ? '删除规格成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadGoodsSpecs(editingGoodsId);
}

// ===== 分类弹窗（新增 / 编辑）=====
function openCategoryModal(id = null, name = '', parentId = 0, pic = '', sortNum = 0) {
    // 新增：清空表单；编辑：回显传入数据
    document.getElementById('category_name').value = name || '';
    document.getElementById('category_parentId').value = parentId || 0;
    document.getElementById('category_pic').value = pic || '';
    document.getElementById('category_sortNum').value = sortNum || 0;
    document.getElementById('categoryModalTitle').textContent = id ? '编辑分类' : '新增分类';
    document.getElementById('categoryModal').style.display = 'flex';
    // 编辑时把 id 存起来（用全局 editingCategoryId）
    if (id) {
        window._editingCategoryId = id;
    } else {
        window._editingCategoryId = null;
    }
}

function openBannerModal(id = null, pic = '', url = '', title = '', sortNum = 0, status = 1) {
    document.getElementById('banner_pic').value = pic || '';
    document.getElementById('banner_url').value = url || '';
    document.getElementById('banner_title').value = title || '';
    document.getElementById('banner_sortNum').value = sortNum || 0;
    document.getElementById('banner_status').value = status ?? 1;
    document.getElementById('bannerModalTitle').textContent = id ? '编辑轮播图' : '新增轮播图';
    document.getElementById('bannerModal').style.display = 'flex';
    window._editingBannerId = id || null;
}

function openKeywordModal(id = null, keyword = '', sortNum = 0, isHot = false) {
    editingKeywordId = id;
    document.getElementById('keyword_keyword').value = keyword;
    document.getElementById('keyword_sortNum').value = sortNum;
    document.getElementById('keyword_isHot').checked = !!isHot;
    document.getElementById('keywordModal').style.display = 'flex';
}

function openCouponModal(id = null, name = '', discount = '', minAmount = '', startTime = '', endTime = '', totalCount = 1000) {
    editingCouponId = id;
    document.getElementById('coupon_name').value = name || '';
    document.getElementById('coupon_discount').value = discount;
    document.getElementById('coupon_minAmount').value = minAmount;
    document.getElementById('coupon_startTime').value = startTime || '';
    document.getElementById('coupon_endTime').value = endTime || '';
    document.getElementById('coupon_totalCount').value = totalCount ?? 1000;
    document.getElementById('couponModalTitle').textContent = id ? '编辑优惠券' : '新增优惠券';
    document.getElementById('couponModal').style.display = 'flex';
}

// ===== 保存操作 =====
async function saveCategory() {
    const body = {
        name: document.getElementById('category_name').value,
        parentId: parseInt(document.getElementById('category_parentId').value) || 0,
        pic: document.getElementById('category_pic').value,
        sortNum: parseInt(document.getElementById('category_sortNum').value) || 0
    };
    const editId = window._editingCategoryId;
    let r;
    if (editId) {
        r = await api('PUT', 'category/update', { id: editId, ...body });
    } else {
        r = await api('POST', 'category/add', body);
    }
    if (r.code === 200) {
        closeModal('categoryModal');
        window._editingCategoryId = null;
        toast(editId ? '编辑分类成功' : '新增分类成功', 'success');
        loadCategories();
    } else {
        toast(r.message || '操作失败', 'error');
    }
}

async function saveBanner() {
    const body = {
        pic: document.getElementById('banner_pic').value,
        url: document.getElementById('banner_url').value,
        title: document.getElementById('banner_title').value,
        sortNum: parseInt(document.getElementById('banner_sortNum').value) || 0,
        status: parseInt(document.getElementById('banner_status').value) || 1
    };
    const editId = window._editingBannerId;
    let r;
    if (editId) {
        r = await api('PUT', 'banner/update', { id: editId, ...body });
    } else {
        r = await api('POST', 'banner/add', body);
    }
    if (r.code === 200) {
        closeModal('bannerModal');
        window._editingBannerId = null;
        toast(editId ? '编辑轮播图成功' : '新增轮播图成功', 'success');
        loadBanners();
    } else {
        toast(r.message || '操作失败', 'error');
    }
}

async function saveKeyword() {
    const keyword = document.getElementById('keyword_keyword').value;
    const sortNum = parseInt(document.getElementById('keyword_sortNum').value) || 0;
    const isHot = document.getElementById('keyword_isHot').checked;
    if (!keyword) { toast('关键词不能为空', 'error'); return; }

    if (editingKeywordId) {
        const r = await api('PUT', 'keyword/update', { id: editingKeywordId, keyword, sortNum, isHot });
        if (r.code === 200) { closeModal('keywordModal'); editingKeywordId = null; toast('编辑关键词成功', 'success'); loadKeywords(); }
        else toast(r.message || '操作失败', 'error');
    } else {
        const r = await api('POST', 'keyword/add', { keyword, sortNum, isHot });
        if (r.code === 200) { closeModal('keywordModal'); toast('新增关键词成功', 'success'); loadKeywords(); }
        else toast(r.message || '操作失败', 'error');
    }
}

async function saveGoods() {
    const title = document.getElementById('goods_title').value;
    const mainPic = document.getElementById('goods_mainPic').value;
    if (!title) { toast('食物名称不能为空', 'error'); return; }

    if (editingGoodsId) {
        const body = {
            id: editingGoodsId,
            typeId: parseInt(document.getElementById('goods_typeId').value) || 1,
            price: parseInt(document.getElementById('goods_price').value) || 0,
            title,
            subTitle: document.getElementById('goods_subTitle').value,
            mainPic,
            status: parseInt(document.getElementById('goods_status').value) || 1,
            sortNum: parseInt(document.getElementById('goods_sortNum').value) || 0
        };
        const r = await api('PUT', 'foods/update', body);
        if (r.code === 200) { closeModal('goodsModal'); editingGoodsId = null; toast('编辑食物成功', 'success'); loadGoods(1); }
        else toast(r.message || '操作失败', 'error');
    } else {
        const body = {
            typeId: parseInt(document.getElementById('goods_typeId').value) || 1,
            price: parseInt(document.getElementById('goods_price').value) || 0,
            title,
            subTitle: document.getElementById('goods_subTitle').value,
            mainPic,
            status: parseInt(document.getElementById('goods_status').value) || 1,
            sortNum: parseInt(document.getElementById('goods_sortNum').value) || 0
        };
        const r = await api('POST', 'foods/add', body);
        if (r.code === 200) { closeModal('goodsModal'); toast('新增食物成功', 'success'); loadGoods(1); }
        else toast(r.message || '操作失败', 'error');
    }
}

async function saveCoupon() {
    const name = document.getElementById('coupon_name').value;
    const discount = parseInt(document.getElementById('coupon_discount').value) || 0;
    const minAmount = parseInt(document.getElementById('coupon_minAmount').value) || 0;
    const startTime = document.getElementById('coupon_startTime').value;
    const endTime = document.getElementById('coupon_endTime').value;
    const totalCount = parseInt(document.getElementById('coupon_totalCount').value) || 1000;
    if (!name || !startTime || !endTime) { toast('名称和时间不能为空', 'error'); return; }

    if (editingCouponId) {
        const r = await api('PUT', 'coupon/update', { id: editingCouponId, name, discount, minAmount, startTime, endTime, totalCount });
        if (r.code === 200) { closeModal('couponModal'); editingCouponId = null; toast('编辑优惠券成功', 'success'); loadCoupons(); }
        else toast(r.message || '操作失败', 'error');
    } else {
        const r = await api('POST', 'coupon/add', { name, discount, minAmount, startTime, endTime, totalCount });
        if (r.code === 200) { closeModal('couponModal'); toast('新增优惠券成功', 'success'); loadCoupons(); }
        else toast(r.message || '操作失败', 'error');
    }
}

// ===== 删除 =====
async function deleteGoods(id) {
    if (!confirm('确定删除该食物？（将下架）')) return;
    const r = await api('DELETE', 'foods/delete', { id });
    toast(r.code === 200 ? '删除食物成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadGoods(1);
}
async function deleteCategory(id) {
    if (!confirm('确定删除该分类？')) return;
    const r = await api('DELETE', 'category/delete', { id });
    toast(r.code === 200 ? '删除分类成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadCategories();
}
async function deleteBanner(id) {
    if (!confirm('确定删除该轮播图？')) return;
    const r = await api('DELETE', 'banner/delete', { id });
    toast(r.code === 200 ? '删除轮播图成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadBanners();
}
async function deleteKeyword(id) {
    if (!confirm('确定删除该关键词？')) return;
    const r = await api('DELETE', 'keyword/delete', { id });
    toast(r.code === 200 ? '删除关键词成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadKeywords();
}
async function deleteCoupon(id) {
    if (!confirm('确定删除该优惠券？')) return;
    const r = await api('DELETE', 'coupon/delete', { id });
    toast(r.code === 200 ? '删除优惠券成功' : r.message || '操作失败', r.code === 200 ? 'success' : 'error');
    if (r.code === 200) loadCoupons();
}

// ===== 全局搜索（顶部栏输入框按回车触发）=====
document.addEventListener('DOMContentLoaded', () => {
    const gs = document.getElementById('globalSearch');
    if (gs) {
        gs.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const kw = gs.value.trim();
                showPage('goods');
                document.getElementById('goodsKeyword').value = kw;
                loadGoods(1);
            }
        });
    }
});

// ===== 启动 =====
if (token) {
    api('GET', 'dashboard/stats').then(r => {
        if (r.code === 200) showMain();
        else { localStorage.removeItem('admin_token'); token = ''; }
    }).catch(() => { localStorage.removeItem('admin_token'); token = ''; });
}
