// ===== Tab 切换 =====
const tabPages = { home: 'page-home', recipe: 'page-recipe', cart: 'page-cart', profile: 'page-profile' };
const tabHistory = ['home']; // 记录底部 Tab 历史

function switchTab(tab) {
    // 隐藏所有页面
    document.querySelectorAll('.page').forEach(p => {
        if (p.id.startsWith('page-')) p.classList.remove('active');
    });
    // 隐藏所有 Tab 按钮
    document.querySelectorAll('.tab-item').forEach(t => t.classList.remove('active'));
    // 显示目标
    document.getElementById(tabPages[tab]).classList.add('active');
    document.querySelector(`.tab-item[data-tab="${tab}"]`).classList.add('active');
    // 隐藏返回按钮（回到 Tab 页时）
    hideBackBtn();
    // 清除子页面状态
    currentSubPage = null;
}

// ===== 页面导航（子页面）=====
let currentSubPage = null;
const tabTabs = ['home', 'recipe', 'cart', 'profile'];

function navigateTo(pageId, params = {}) {
    // 如果目标是 Tab 页面
    if (tabTabs.includes(pageId)) { switchTab(pageId); return; }
    // 隐藏所有页面
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    // 显示子页面
    const target = document.getElementById('page-' + pageId);
    if (target) target.classList.add('active');
    // 显示返回按钮（如果 Tab 栏没有自己的返回）
    currentSubPage = pageId;
    showBackBtn();
    // 滚动到顶部
    const content = document.querySelector('.content');
    content.scrollTop = 0;
}

function goBack() {
    // 回到上一个 Tab（简化：回到首页或当前激活的 Tab）
    const activeTab = document.querySelector('.tab-item.active');
    const tab = activeTab ? activeTab.dataset.tab : 'home';
    switchTab(tab);
}

function showBackBtn() { /* 子页面自带 back btn in their header */ }
function hideBackBtn() {}

// ===== Toast =====
let toastTimer = null;
function toast(msg) {
    const el = document.getElementById('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 1800);
}

// ===== 购物车勾选 =====
document.addEventListener('click', (e) => {
    if (e.target.classList.contains('checkbox')) {
        e.target.classList.toggle('checked');
    }
    if (e.target.classList.contains('step-minus')) {
        const num = e.target.parentElement.querySelector('span');
        num.textContent = Math.max(1, parseInt(num.textContent) - 1);
    }
    if (e.target.classList.contains('step-plus')) {
        const num = e.target.parentElement.querySelector('span');
        num.textContent = parseInt(num.textContent) + 1;
    }
});

// ===== 搜索框聚焦 =====
document.addEventListener('DOMContentLoaded', () => {
    // 阻止聚焦搜索框时跳转（首页的搜索栏）
    const homeSearch = document.querySelector('#page-home .search-bar');
    if (homeSearch) {
        homeSearch.addEventListener('click', (e) => {
            e.stopPropagation();
            navigateTo('search');
        });
    }

    // 初始化倒计时（模拟）
    const countdown = document.querySelector('.countdown');
    if (countdown) {
        let [h, m, s] = [2, 34, 51];
        setInterval(() => {
            s--;
            if (s < 0) { s = 59; m--; }
            if (m < 0) { m = 59; h--; }
            if (h < 0) { h = 2; m = 34; }
            countdown.textContent = `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
        }, 1000);
    }

    // 菜谱分类筛选
    const chips = document.querySelectorAll('#page-recipe .chip');
    chips.forEach(chip => {
        chip.addEventListener('click', () => {
            chips.forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            toast('筛选：' + chip.textContent);
        });
    });
});

// ===== 占位函数 =====
function toggleCartEdit() { toast('编辑模式'); }
function scrollToCategory(cat) { toast('查看分类：' + cat); }
