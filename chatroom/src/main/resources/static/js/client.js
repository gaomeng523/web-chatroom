/**
 * 网页聊天室 - 客户端主逻辑
 */

// 所有 ajax 请求统一携带登录 token（头名需与后端 Constant.USER_TOKEN_HEADER 一致）
$.ajaxSetup({
    headers: { 'User-Token': localStorage.getItem('token') || '' }
});

// token 失效时统一回登录页，省得每个接口各写一遍
$(document).ajaxError(function(event, xhr) {
    if (xhr.status === 401) {
        localStorage.removeItem('token');
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        alert('登录已过期，请重新登录！');
        location.assign('/login.html');
    }
});

let websocket = null;
let selfUserId = 0;
let selfUsername = '';

const avatarColors = ['avatar-green','avatar-blue','avatar-purple','avatar-orange','avatar-pink','avatar-teal'];

function getAvatarColor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return avatarColors[Math.abs(hash) % avatarColors.length];
}

function getAvatarText(name) { return name ? name.charAt(0).toUpperCase() : '?'; }

function escapeHtml(text) {
    let div = document.createElement('div');
    div.appendChild(document.createTextNode(text));
    return div.innerHTML;
}

function formatTime(timeStr) {
    if (!timeStr) return '';
    if (typeof timeStr === 'string' && timeStr.indexOf('-') > -1) {
        let parts = timeStr.split(' ');
        if (parts.length >= 2) {
            let tp = parts[1].split(':');
            return tp[0] + ':' + tp[1];
        }
        return timeStr;
    }
    return timeStr;
}

function scrollBottom(elem) {
    elem.scrollTo(0, elem.scrollHeight - elem.offsetHeight);
}

// ====== 1. 获取用户信息 ======
function getUserInfo() {
    $.ajax({
        type: 'get', url: '/user/userInfo',
        success: function(body) {
            if (body && body.userId > 0) {
                selfUserId = body.userId;
                selfUsername = body.username;
                document.querySelector('#user-name').innerHTML = body.username;
                let av = document.querySelector('#user-avatar');
                av.innerHTML = getAvatarText(body.username);
                av.className = 'avatar ' + getAvatarColor(body.username);
                document.querySelector('#user-name').setAttribute('user-id', body.userId);
            } else {
                location.assign('/login.html');
            }
        },
        // 401 已由上面的全局 ajaxError 处理（弹提示 + 跳登录页），这里只管跳转
        error: function(xhr) {
            if (xhr.status !== 401) { alert("获取用户信息失败！"); }
            location.assign('/login.html');
        }
    });
}

// ====== 2. 标签页切换 ======
function initSwitchTab() {
    let tabSession = document.querySelector('#tab-session');
    let tabFriend = document.querySelector('#tab-friend');
    let sessionList = document.querySelector('#session-list');
    let friendPanel = document.querySelector('#friend-panel');

    tabSession.onclick = function() {
        tabSession.classList.add('active');
        tabFriend.classList.remove('active');
        sessionList.classList.remove('hide');
        friendPanel.classList.add('hide');
    };
    tabFriend.onclick = function() {
        tabFriend.classList.add('active');
        tabSession.classList.remove('active');
        friendPanel.classList.remove('hide');
        sessionList.classList.add('hide');
    };
}

// ====== 3. 好友列表 ======
function getFriendList() {
    $.ajax({
        type: 'get', url: '/friendList',
        success: function(body) {
            let fl = document.querySelector('#friend-list');
            fl.innerHTML = '';
            if (!body || body.length === 0) {
                fl.innerHTML = '<li class="list-tip">还没有好友<br>用上方搜索框找人添加吧</li>';
                return;
            }
            for (let f of body) {
                let li = document.createElement('li');
                li.setAttribute('friend-id', f.friendId);
                let av = document.createElement('div');
                av.className = 'item-avatar ' + getAvatarColor(f.friendName);
                av.innerHTML = getAvatarText(f.friendName);
                let ct = document.createElement('div');
                ct.className = 'item-content';
                ct.innerHTML = '<h4>' + escapeHtml(f.friendName) + '</h4>';
                li.appendChild(av); li.appendChild(ct);
                fl.appendChild(li);
                li.onclick = function() { clickFriend(f, li); };
            }
        }
    });
}

// ====== 4. 会话列表 ======
function getSessionList() {
    $.ajax({
        type: 'get', url: '/sessionList',
        success: function(body) {
            let sl = document.querySelector('#session-list');
            sl.innerHTML = '';
            for (let s of body) {
                let lm = s.lastMessage || '';
                if (lm.length > 15) lm = lm.substring(0, 15) + '...';
                let fn = (s.friends && s.friends.length > 0) ? s.friends[0].friendName : '未知会话';

                let li = document.createElement('li');
                li.setAttribute('message-session-id', s.sessionId);
                let av = document.createElement('div');
                av.className = 'item-avatar ' + getAvatarColor(fn);
                av.innerHTML = getAvatarText(fn);
                let ct = document.createElement('div');
                ct.className = 'item-content';
                ct.innerHTML = '<h3>' + escapeHtml(fn) + '</h3><p>' + escapeHtml(lm) + '</p>';
                li.appendChild(av); li.appendChild(ct);
                setUnreadBadge(li, s.unreadCount);
                sl.appendChild(li);
                li.onclick = function() { clickSession(li); };
            }
        }
    });
}

// ====== 5. 点击会话 ======
function clickSession(li) {
    let all = document.querySelectorAll('#session-list>li');
    for (let s of all) { if (s === li) s.classList.add('selected'); else s.classList.remove('selected'); }
    let sessionId = li.getAttribute('message-session-id');

    // 乐观更新：先把红点清掉，别让用户看到"自己正盯着的会话还有未读"
    clearUnreadBadge(li);
    // 再通知后端把已读游标推上去
    markSessionRead(sessionId);

    getHistoryMessage(sessionId);
}

// ====== 6. 点击好友 → 创建/切换会话 ======
function clickFriend(friend, friendLi) {
    // 好友列表里高亮当前点的人
    for (let item of document.querySelectorAll('#friend-list>li')) {
        if (item === friendLi) item.classList.add('selected');
        else item.classList.remove('selected');
    }
    let sessionLi = findSessionByName(friend.friendName);
    let sl = document.querySelector('#session-list');
    if (sessionLi) {
        sl.insertBefore(sessionLi, sl.children[0]);
        sessionLi.click();
    } else {
        sessionLi = document.createElement('li');
        sessionLi.setAttribute('message-session-id', '');
        let av = document.createElement('div');
        av.className = 'item-avatar ' + getAvatarColor(friend.friendName);
        av.innerHTML = getAvatarText(friend.friendName);
        let ct = document.createElement('div');
        ct.className = 'item-content';
        ct.innerHTML = '<h3>' + escapeHtml(friend.friendName) + '</h3><p></p>';
        sessionLi.appendChild(av); sessionLi.appendChild(ct);
        sl.insertBefore(sessionLi, sl.children[0]);
        sessionLi.onclick = function() { clickSession(sessionLi); };
        sessionLi.click();
        createSession(friend.friendId, sessionLi);
    }
    document.querySelector('#tab-session').click();
}

function findSessionByName(name) {
    for (let li of document.querySelectorAll('#session-list>li')) {
        let h3 = li.querySelector('h3');
        if (h3 && name === h3.innerHTML.trim()) return li;
    }
    return null;
}

function findSessionById(id) {
    for (let li of document.querySelectorAll('#session-list>li')) {
        if (li.getAttribute('message-session-id') === String(id)) return li;
    }
    return null;
}

// ====== 6.1 未读红点（三个小工具）======
function setUnreadBadge(li, count) {
    if (!li) return;
    let old = li.querySelector('.unread-badge');
    if (old) old.remove();
    li.removeAttribute('unread-count');
    if (!count || count <= 0) return;
    // 把数字挂到属性上，方便调试时一眼看到真实值
    li.setAttribute('unread-count', count);
    let badge = document.createElement('span');
    badge.className = 'unread-badge';
    // 超过 99 显示 99+，否则数字会把红点撑变形
    badge.innerHTML = count > 99 ? '99+' : count;
    li.appendChild(badge);
}

function clearUnreadBadge(li) {
    if (!li) return;
    let badge = li.querySelector('.unread-badge');
    if (badge) badge.remove();
    li.removeAttribute('unread-count');
}

// 上报"这个会话我读了"。故意不弹错误提示：已读上报失败不影响聊天，
// 下次拉会话列表会拿回真实未读数，自动纠正。
function markSessionRead(sessionId) {
    if (!sessionId) return;
    $.ajax({
        type: 'get', url: '/sessionRead?sessionId=' + sessionId,
        error: function(xhr) {
            if (xhr.status !== 401) console.log('标记已读失败，状态码: ' + xhr.status);
        }
    });
}

function createSession(friendId, sessionLi) {
    $.ajax({
        type: 'post', url: '/session?toUserId=' + friendId,
        success: function(body) { sessionLi.setAttribute('message-session-id', body.sessionId); },
        error: function() { alert("创建会话失败!"); }
    });
}

// ====== 7. 获取历史消息 ======
function getHistoryMessage(sessionId) {
    let titleDiv = document.querySelector('#chat-title');
    let msgDiv = document.querySelector('#message-show');
    msgDiv.innerHTML = '';
    let h3 = document.querySelector('#session-list>.selected>h3');
    if (h3) titleDiv.innerHTML = h3.innerHTML.trim();

    if (!sessionId) {
        msgDiv.innerHTML = '<div class="empty-state"><div class="empty-icon">&#128172;</div><p>开始新的对话吧</p></div>';
        return;
    }

    $.ajax({
        type: 'get', url: '/message?sessionId=' + sessionId,
        success: function(body) {
            for (let m of body) addMessage(msgDiv, m);
            scrollBottom(msgDiv);
        }
    });
}

// ====== 8. 添加消息到界面 ======
function addMessage(container, msg) {
    let fromName = msg.fromName || '未知用户';
    let isSelf = (fromName === selfUsername);
    let div = document.createElement('div');
    div.className = 'message ' + (isSelf ? 'message-right' : 'message-left');

    let av = document.createElement('div');
    av.className = 'msg-avatar ' + getAvatarColor(fromName);
    av.innerHTML = getAvatarText(fromName);

    let bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = '<div class="sender-info">' + escapeHtml(fromName) + '</div>'
        + '<div class="msg-content">' + escapeHtml(msg.content || '') + '</div>'
        + '<div class="msg-time">' + formatTime(msg.postTime) + '</div>';

    div.appendChild(av);
    div.appendChild(bubble);
    container.appendChild(div);
}

// ====== 9. WebSocket ======
function initWebSocket() {
    // 协议和主机从当前页面取，避免端口写死（本地 8080 / 测试 18080 都能用）
    let proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    // 浏览器原生 WebSocket 不支持自定义请求头，User-Token 那套用不了，
    // token 只能挂在 URL query 上，由后端 AuthHandshakeInterceptor 解析。
    let wsUrl = proto + location.host + '/ws/message?token=' + encodeURIComponent(localStorage.getItem('token') || '');
    websocket = new WebSocket(wsUrl);

    websocket.onopen = function() {
        console.log("WebSocket 连接成功: " + wsUrl);
        document.querySelector('#chat-status').innerHTML = '在线';
    };
    websocket.onclose = function() {
        document.querySelector('#chat-status').innerHTML = '离线';
    };
    websocket.onerror = function() { console.log("WebSocket 异常"); };

    websocket.onmessage = function(event) {
        let resp = JSON.parse(event.data);
        if (resp.type === 'message') handleMessage(resp);
        else if (resp.type === 'addFriendRequest') handleAddFriendRequest(resp);
        else if (resp.type === 'acceptFriend') handleAcceptFriend(resp);
        else if (resp.type === 'error') alert(resp.content || '消息发送失败');
    };

    window.onbeforeunload = function() { if (websocket) websocket.close(); };
}

// ====== 10. 发送消息 ======
function initSendButton() {
    let sendBtn = document.querySelector('#send-btn');
    let input = document.querySelector('#message-textarea');
    sendBtn.onclick = function() { sendMessage(); };
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });
}

function sendMessage() {
    let input = document.querySelector('#message-textarea');
    let content = input.value.trim();
    if (!content) return;
    let sel = document.querySelector('#session-list>.selected');
    if (!sel) { alert('请先选择会话！'); return; }
    let sid = sel.getAttribute('message-session-id');
    if (!sid) { alert('会话未创建成功，请稍后重试！'); return; }
    websocket.send(JSON.stringify({ type: 'message', sessionId: parseInt(sid), content: content }));
    input.value = '';
}

// ====== 11. 处理实时消息 ======
function handleMessage(resp) {
    let fromName = resp.fromName || '未知用户';
    let sl = document.querySelector('#session-list');
    let cur = findSessionById(resp.sessionId);
    if (!cur) {
        cur = document.createElement('li');
        cur.setAttribute('message-session-id', resp.sessionId);
        let av = document.createElement('div');
        av.className = 'item-avatar ' + getAvatarColor(fromName);
        av.innerHTML = getAvatarText(fromName);
        let ct = document.createElement('div');
        ct.className = 'item-content';
        ct.innerHTML = '<h3>' + escapeHtml(fromName) + '</h3><p></p>';
        cur.appendChild(av); cur.appendChild(ct);
        cur.onclick = function() { clickSession(cur); };
        sl.insertBefore(cur, sl.children[0]);
    }
    let p = cur.querySelector('p');
    if (p) {
        let pv = resp.content || '';
        p.innerHTML = escapeHtml(pv.length > 15 ? pv.substring(0, 15) + '...' : pv);
    }
    sl.insertBefore(cur, sl.children[0]);

    if (cur.classList.contains('selected')) {
        // 用户正看着这个会话：直接算已读，红点不该闪一下再消失
        clearUnreadBadge(cur);
        markSessionRead(resp.sessionId);
        let ms = document.querySelector('#message-show');
        addMessage(ms, resp);
        scrollBottom(ms);
    } else if (typeof resp.unreadCount === 'number') {
        // 没在看这个会话：用后端给的权威未读数显示红点。
        // 不在这里自己 +1 是因为页面刚刷新、多标签页、消息撤回都会让 +1 算不准；
        // 后端对"发送者自己"那条推送不带 unreadCount，所以这里也不会误伤自己发的消息。
        setUnreadBadge(cur, resp.unreadCount);
    }
}

// ====== 12. 搜索好友 ======
function initFindFriend() {
    let btn = document.querySelector('#search-btn');
    let input = document.querySelector('#search-input');
    btn.onclick = function() { doSearch(); };
    input.addEventListener('keydown', function(e) { if (e.key === 'Enter') doSearch(); });
}

function doSearch() {
    let name = document.querySelector('#search-input').value.trim();
    if (!name) return;
    $.ajax({
        type: 'get', url: '/findFriend?name=' + encodeURIComponent(name),
        success: function(body) {
            document.querySelector('#chat-title').innerHTML = '查找结果';
            let ms = document.querySelector('#message-show');
            ms.innerHTML = '';
            if (!body || body.length === 0) {
                ms.innerHTML = '<div class="empty-state"><div class="empty-icon">&#128270;</div><p>没有找到匹配的用户</p></div>';
                return;
            }
            for (let f of body) {
                let item = document.createElement('div');
                item.className = 'search-result-item';
                let av = document.createElement('div');
                av.className = 'msg-avatar ' + getAvatarColor(f.friendName);
                av.innerHTML = getAvatarText(f.friendName);
                let nm = document.createElement('span');
                nm.className = 'result-name';
                nm.innerHTML = escapeHtml(f.friendName);
                let ri = document.createElement('input');
                ri.type = 'text'; ri.className = 'result-reason'; ri.placeholder = '验证消息';
                let ab = document.createElement('button');
                ab.className = 'result-add-btn'; ab.innerHTML = '+';
                let fid = f.friendId;
                ab.onclick = function() { sendAddFriend(fid, ri.value.trim() || '你好，我想加你为好友', ab); };
                item.appendChild(av); item.appendChild(nm); item.appendChild(ri); item.appendChild(ab);
                ms.appendChild(item);
            }
        }
    });
}

function sendAddFriend(friendId, reason, btn) {
    if (btn) { btn.disabled = true; }
    $.ajax({
        type: 'get', url: '/addFriend?friendId=' + friendId + '&reason=' + encodeURIComponent(reason),
        success: function() {
            alert('添加好友请求已发送！');
            if (btn) { btn.innerHTML = '&#10003;'; btn.style.background = '#b0b0c8'; }
        },
        error: function(xhr) {
            if (xhr.status !== 401) { alert(xhr.responseJSON ? xhr.responseJSON.message : '添加好友失败！'); }
            if (btn) { btn.disabled = false; }
        }
    });
}

// ====== 13. 好友请求 ======
function getAddFriendRequest() {
    $.ajax({
        type: 'get', url: '/getFriendRequest',
        success: function(body) {
            // 每次拉取先清空，避免重复渲染
            document.querySelector('#request-list').innerHTML = '';
            if (!body || body.length === 0) { updateRequestCount(); return; }
            for (let item of body) {
                addFriendRequestUI(item.fromUserId, item.fromUserName, item.reason, true);
            }
        }
    });
}

function handleAddFriendRequest(resp) {
    addFriendRequestUI(resp.fromUserId, resp.fromUserName, resp.reason);
}

function updateRequestCount() {
    let count = document.querySelectorAll('#request-list>li').length;
    let section = document.querySelector('#request-section');
    document.querySelector('#request-count').innerHTML = count;
    // 没有请求时整块区域隐藏
    if (count > 0) section.classList.remove('hide');
    else section.classList.add('hide');
}

function addFriendRequestUI(fromUserId, fromUserName, reason, silent) {
    // 同一个人已经在列表里就不重复加
    if (document.querySelector('#request-list>li[from-user-id="' + fromUserId + '"]')) return;

    let ul = document.querySelector('#request-list');
    let li = document.createElement('li');
    li.setAttribute('from-user-id', fromUserId);

    let top = document.createElement('div');
    top.className = 'req-top';
    let av = document.createElement('div');
    av.className = 'item-avatar ' + getAvatarColor(fromUserName);
    av.innerHTML = getAvatarText(fromUserName);
    let info = document.createElement('div');
    info.className = 'req-info';
    info.innerHTML = '<h3>' + escapeHtml(fromUserName) + ' 请求添加好友</h3>'
        + '<p>' + escapeHtml(reason || '') + '</p>';
    top.appendChild(av); top.appendChild(info);

    let actions = document.createElement('div');
    actions.className = 'friend-request-actions';

    let btnA = document.createElement('button');
    btnA.className = 'btn-accept'; btnA.innerHTML = '接受';
    btnA.onclick = function(e) {
        e.stopPropagation();
        $.ajax({
            type: 'get', url: '/acceptFriend?friendId=' + fromUserId,
            success: function() {
                alert("已通过 " + fromUserName + " 的好友申请！");
                getFriendList();
                ul.removeChild(li);
                updateRequestCount();
            },
            error: function(xhr) {
                if (xhr.status !== 401) { alert(xhr.responseJSON ? xhr.responseJSON.message : "处理失败！"); }
            }
        });
    };

    let btnR = document.createElement('button');
    btnR.className = 'btn-reject'; btnR.innerHTML = '拒绝';
    btnR.onclick = function(e) {
        e.stopPropagation();
        $.ajax({
            type: 'get', url: '/rejectFriend?friendId=' + fromUserId,
            success: function() {
                alert("已拒绝 " + fromUserName + " 的好友申请。");
                ul.removeChild(li);
                updateRequestCount();
            },
            error: function(xhr) {
                if (xhr.status !== 401) { alert(xhr.responseJSON ? xhr.responseJSON.message : "处理失败！"); }
            }
        });
    };

    actions.appendChild(btnA); actions.appendChild(btnR);
    li.appendChild(top); li.appendChild(actions);
    ul.insertBefore(li, ul.children[0]);

    // 实时推送来的新请求才自动切到好友 tab；页面初始化拉取时不打扰用户
    if (!silent) { document.querySelector('#tab-friend').click(); }
    updateRequestCount();
}

function handleAcceptFriend(resp) {
    getFriendList();
    alert(resp.fromUserName + ' 已通过你的好友申请！');
}

// ====== 初始化 ======
getUserInfo();
initSwitchTab();
getFriendList();
getSessionList();
getAddFriendRequest();
initWebSocket();
initSendButton();
initFindFriend();
