// ===== 全局状态 =====
let ADMIN_TOKEN = localStorage.getItem('admin_token') || '';
// 同源部署：API 前缀为空。如果前后端分离部署，改成完整域名如 "https://api.shop.com"
const API_BASE = window.location.origin;

// ===== 工具 =====
function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.style.display = 'block';
    setTimeout(() => t.style.display = 'none', 2000);
}

async function api(method, path, body = null) {
    const opts = {
        method,
        headers: { 'Content-Type': 'application/json' },
    };
    // AdminAuth 拦截器直接取 Authorization header 值当 token，不用 Bearer 前缀
    if (ADMIN_TOKEN) opts.headers['Authorization'] = ADMIN_TOKEN;
    if (body) opts.body = JSON.stringify(body);

    const res = await fetch(API_BASE + '/admin/' + path, opts);
    const data = await res.json();

    if (data.code === 401 && path !== 'login') {
        localStorage.removeItem('admin_token');
        location.reload();
        return null;
    }
    return data;
}

function closeModal(id) { document.getElementById(id).style.display = 'none'; }

function showPage(name) {
    document.querySelectorAll('.page').forEach(p => p.style.display = 'none');
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
    document.getElementById('page-' + name).style.display = 'block';
    document.querySelector(`.nav-item[data-page="${name}"]`).classList.add('active');

    // 懒加载数据
    if (name === 'dashboard') loadDashboard();
    if (name === 'goods') loadGoods();
    if (name === 'orders') loadOrders();
    if (name === 'users') loadUsers();
    if (name === 'coupons') loadCoupons();
}

function priceToYuan(fen) { return '¥' + (fen / 100).toFixed(2); }

// ===== 登录 =====
async function doLogin() {
    const username = document.getElementById('loginUsername').value;
    const password = document.getElementById('loginPassword').value;
    const errEl = document.getElementById('loginError');
    errEl.textContent = '';

    const data = await api('POST', 'login', { username, password });
    if (data && data.code === 200) {
        ADMIN_TOKEN = data.data.token;
        localStorage.setItem('admin_token', ADMIN_TOKEN);
        document.getElementById('adminName').textContent = data.data.username;
        document.getElementById('loginPage').style.display = 'none';
        document.getElementById('mainPage').style.display = 'flex';
        showPage('dashboard');
    } else {
        errEl.textContent = data?.message || '登录失败';
    }
}

function doLogout() {
    localStorage.removeItem('admin_token');
    ADMIN_TOKEN = '';
    location.reload();
}

// ===== 启动 =====
(function init() {
    if (ADMIN_TOKEN) {
        document.getElementById('loginPage').style.display = 'none';
        document.getElementById('mainPage').style.display = 'flex';
        showPage('dashboard');
    }
})();

// ===== 仪表盘 =====
async function loadDashboard() {
    const data = await api('GET', 'dashboard/stats');
    if (data?.code === 200) {
        const d = data.data;
        document.getElementById('statGoods').textContent = d.goodsCount;
        document.getElementById('statOrders').textContent = d.orderCount;
        document.getElementById('statUsers').textContent = d.userCount;
        document.getElementById('statSales').textContent = '¥' + (d.totalSales / 100).toFixed(2);
    }
}

// ===== 商品管理 =====
async function loadGoods() {
    const keyword = document.getElementById('goodsKeyword')?.value || '';
    const data = await api('GET', 'goods/list', { keyword, page: 1, size: 50 });
    if (data?.code === 200) {
        const list = data.data.list;
        const tbody = document.getElementById('goodsTableBody');
        tbody.innerHTML = list.map(g => `
            <tr>
                <td>${g.id}</td>
                <td>${g.mainPic ? `<img src="${g.mainPic}" onerror="this.style.display='none'">` : ''}</td>
                <td>${g.title}</td>
                <td>${priceToYuan(g.price)}</td>
                <td>${g.sold}</td>
                <td><span class="badge ${g.status === 1 ? 'badge-green' : 'badge-gray'}">${g.status === 1 ? '上架' : '下架'}</span></td>
                <td>
                    <button class="btn btn-sm btn-primary" onclick="openGoodsModal(${g.id})">编辑</button>
                    <button class="btn btn-sm btn-danger" onclick="deleteGoods(${g.id})">删</button>
                </td>
            </tr>
        `).join('');
    }
}

async function deleteGoods(id) {
    if (!confirm('确认删除（下架）该商品？')) return;
    const data = await api('DELETE', 'goods/delete', { id });
    if (data?.code === 200) { toast('删除成功'); loadGoods(); }
    else toast(data?.message || '删除失败');
}

let editingGoodsId = null;
function openGoodsModal(id) {
    editingGoodsId = id || null;
    document.getElementById('goodsModalTitle').textContent = id ? '编辑商品' : '新增商品';
    document.getElementById('goodsModal').style.display = 'flex';
    if (!id) {
        ['goods_typeId', 'goods_price', 'goods_title', 'goods_subTitle', 'goods_mainPic', 'goods_sortNum'].forEach(id => {
            document.getElementById(id).value = '';
        });
        document.getElementById('goods_status').value = '1';
    }
}

async function saveGoods() {
    const payload = {
        typeId: parseInt(document.getElementById('goods_typeId').value) || 1,
        title: document.getElementById('goods_title').value,
        subTitle: document.getElementById('goods_subTitle').value || null,
        mainPic: document.getElementById('goods_mainPic').value,
        price: parseInt(document.getElementById('goods_price').value) || 0,
        status: parseInt(document.getElementById('goods_status').value),
        sortNum: parseInt(document.getElementById('goods_sortNum').value) || 0,
    };

    if (!payload.title || !payload.mainPic) { toast('标题和主图必填'); return; }

    const path = editingGoodsId ? 'goods/update' : 'goods/add';
    if (editingGoodsId) payload.id = editingGoodsId;

    const data = await api(editingGoodsId ? 'PUT' : 'POST', path, payload);
    if (data?.code === 200) {
        toast('保存成功');
        closeModal('goodsModal');
        loadGoods();
    } else {
        toast(data?.message || '保存失败');
    }
}

// ===== 订单管理 =====
async function loadOrders() {
    const data = await api('GET', 'order/list', { page: 1, size: 50 });
    if (data?.code === 200) {
        const list = data.data.list;
        const tbody = document.getElementById('ordersTableBody');
        const statusMap = { 0: 'badge-gray|待支付', 1: 'badge-blue|已支付', 2: 'badge-yellow|已发货', 3: 'badge-green|已签收', 4: 'badge-gray|已取消', 5: 'badge-red|退款中', 6: 'badge-red|已退款' };
        tbody.innerHTML = list.map(o => {
            const s = statusMap[o.status] || 'badge-gray|未知';
            const [cls, label] = s.split('|');
            const canShip = o.status === 1 ? `<button class="btn btn-sm btn-primary" onclick="shipOrder(${o.id})">发货</button>` : '';
            return `
                <tr>
                    <td>${o.id}</td>
                    <td>${o.orderNo}</td>
                    <td>用户#${o.userId}</td>
                    <td>${priceToYuan(o.totalPrice)}</td>
                    <td><span class="badge ${cls}">${label}</span></td>
                    <td>${o.createTime}</td>
                    <td>${canShip}</td>
                </tr>
            `;
        }).join('');
    }
}

async function shipOrder(id) {
    if (!confirm('确认发货？')) return;
    const data = await api('POST', 'order/ship', { orderId: id });
    if (data?.code === 200) { toast('发货成功'); loadOrders(); }
    else toast(data?.message || '发货失败');
}

// ===== 用户管理 =====
async function loadUsers() {
    const data = await api('GET', 'user/list', { page: 1, size: 50 });
    if (data?.code === 200) {
        const list = data.data.list;
        const tbody = document.getElementById('usersTableBody');
        tbody.innerHTML = list.map(u => `
            <tr>
                <td>${u.id}</td>
                <td>${u.phone}</td>
                <td>${u.nickname}</td>
                <td><span class="badge ${u.status === 1 ? 'badge-green' : 'badge-red'}">${u.status === 1 ? '正常' : '禁用'}</span></td>
                <td>${u.createTime}</td>
                <td>
                    <button class="btn btn-sm ${u.status === 1 ? 'btn-danger' : 'btn-primary'}" onclick="toggleUserStatus(${u.id}, ${u.status})">
                        ${u.status === 1 ? '禁用' : '启用'}
                    </button>
                </td>
            </tr>
        `).join('');
    }
}

async function toggleUserStatus(userId, currentStatus) {
    const newStatus = currentStatus === 1 ? 0 : 1;
    const data = await api('POST', 'user/updateStatus', { userId, status: newStatus });
    if (data?.code === 200) { toast('更新成功'); loadUsers(); }
    else toast(data?.message || '更新失败');
}

// ===== 优惠券管理 =====
async function loadCoupons() {
    const data = await api('GET', 'coupon/list');
    if (data?.code === 200) {
        const list = data.data;
        const tbody = document.getElementById('couponsTableBody');
        tbody.innerHTML = list.map(c => `
            <tr>
                <td>${c.id}</td>
                <td>${c.name}</td>
                <td>${priceToYuan(c.discount)}</td>
                <td>${priceToYuan(c.minAmount)}</td>
                <td>${c.remainCount}</td>
                <td>${c.startTime} ~ ${c.endTime}</td>
                <td><span class="badge ${c.status === 1 ? 'badge-green' : 'badge-gray'}">${c.status === 1 ? '有效' : '失效'}</span></td>
            </tr>
        `).join('');
    }
}

function openCouponModal() {
    document.getElementById('couponModal').style.display = 'flex';
}

async function saveCoupon() {
    const payload = {
        name: document.getElementById('coupon_name').value,
        discount: parseInt(document.getElementById('coupon_discount').value) || 0,
        minAmount: parseInt(document.getElementById('coupon_minAmount').value) || 0,
        totalCount: parseInt(document.getElementById('coupon_totalCount').value) || 0,
        startTime: document.getElementById('coupon_startTime').value,
        endTime: document.getElementById('coupon_endTime').value,
    };
    if (!payload.name || !payload.startTime || !payload.endTime) { toast('必填项缺失'); return; }

    const data = await api('POST', 'coupon/add', payload);
    if (data?.code === 200) { toast('添加成功'); closeModal('couponModal'); loadCoupons(); }
    else toast(data?.message || '添加失败');
}
