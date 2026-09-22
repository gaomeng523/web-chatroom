/**
 * 网页聊天室 - 客户端主逻辑
 */

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
        type: 'get', url: '/userInfo',
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
        error: function() { alert("获取用户信息失败！"); location.assign('/login.html'); }
    });
}

// ====== 2. 标签页切换 ======
function initSwitchTab() {
    let tabSession = document.querySelector('#tab-session');
    let tabFriend = document.querySelector('#tab-friend');
    let sessionList = document.querySelector('#session-list');
    let friendList = document.querySelector('#friend-list');

    tabSession.onclick = function() {
        tabSession.classList.add('active');
        tabFriend.classList.remove('active');
        sessionList.classList.remove('hide');
        friendList.classList.add('hide');
    };
    tabFriend.onclick = function() {
        tabFriend.classList.add('active');
        tabSession.classList.remove('active');
        friendList.classList.remove('hide');
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
            for (let f of body) {
                let li = document.createElement('li');
                li.setAttribute('friend-id', f.friendId);
                let av = document.createElement('div');
                av.className = 'item-avatar ' + getAvatarColor(f.friendName);
                av.innerHTML = getAvatarText(f.friendName);
                let ct = document.createElement('div');
                ct.className = 'item-content';
                ct.innerHTML = '<h4>' + f.friendName + '</h4>';
                li.appendChild(av); li.appendChild(ct);
                fl.appendChild(li);
                li.onclick = function() { clickFriend(f); };
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
                ct.innerHTML = '<h3>' + fn + '</h3><p>' + lm + '</p>';
                li.appendChild(av); li.appendChild(ct);
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
    getHistoryMessage(li.getAttribute('message-session-id'));
}

// ====== 6. 点击好友 → 创建/切换会话 ======
function clickFriend(friend) {
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
        ct.innerHTML = '<h3>' + friend.friendName + '</h3><p></p>';
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
    let isSelf = (msg.fromName === selfUsername);
    let div = document.createElement('div');
    div.className = 'message ' + (isSelf ? 'message-right' : 'message-left');

    let av = document.createElement('div');
    av.className = 'msg-avatar ' + getAvatarColor(msg.fromName);
    av.innerHTML = getAvatarText(msg.fromName);

    let bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.innerHTML = '<div class="sender-info">' + msg.fromName + '</div>'
        + '<div class="msg-content">' + escapeHtml(msg.content) + '</div>'
        + '<div class="msg-time">' + formatTime(msg.postTime) + '</div>';

    div.appendChild(av);
    div.appendChild(bubble);
    container.appendChild(div);
}

// ====== 9. WebSocket ======
function initWebSocket() {
    websocket = new WebSocket("ws://127.0.0.1:8080/message");

    websocket.onopen = function() {
        console.log("WebSocket 连接成功");
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
    let sl = document.querySelector('#session-list');
    let cur = findSessionById(resp.sessionId);
    if (!cur) {
        cur = document.createElement('li');
        cur.setAttribute('message-session-id', resp.sessionId);
        let av = document.createElement('div');
        av.className = 'item-avatar ' + getAvatarColor(resp.fromName);
        av.innerHTML = getAvatarText(resp.fromName);
        let ct = document.createElement('div');
        ct.className = 'item-content';
        ct.innerHTML = '<h3>' + resp.fromName + '</h3><p></p>';
        cur.appendChild(av); cur.appendChild(ct);
        cur.onclick = function() { clickSession(cur); };
        sl.insertBefore(cur, sl.children[0]);
    }
    let p = cur.querySelector('p');
    if (p) { let pv = resp.content; p.innerHTML = pv.length > 15 ? pv.substring(0, 15) + '...' : pv; }
    sl.insertBefore(cur, sl.children[0]);

    if (cur.classList.contains('selected')) {
        let ms = document.querySelector('#message-show');
        addMessage(ms, resp);
        scrollBottom(ms);
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
            if (body.length === 0) {
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
                nm.className = 'result-name'; nm.innerHTML = f.friendName;
                let ri = document.createElement('input');
                ri.type = 'text'; ri.className = 'result-reason'; ri.placeholder = '验证消息';
                let ab = document.createElement('button');
                ab.className = 'result-add-btn'; ab.innerHTML = '+';
                let fid = f.friendId;
                ab.onclick = function() { sendAddFriend(fid, ri.value.trim() || '你好，我想加你为好友'); };
                item.appendChild(av); item.appendChild(nm); item.appendChild(ri); item.appendChild(ab);
                ms.appendChild(item);
            }
        }
    });
}

function sendAddFriend(friendId, reason) {
    $.ajax({
        type: 'get', url: '/addFriend?friendId=' + friendId + '&reason=' + encodeURIComponent(reason),
        success: function() { alert('添加好友请求已发送！'); },
        error: function() { alert('添加好友失败！'); }
    });
}

// ====== 13. 好友请求 ======
function getAddFriendRequest() {
    $.ajax({
        type: 'get', url: '/getFriendRequest',
        success: function(body) { for (let item of body) addFriendRequestUI(item.fromUserId, item.fromUserName, item.reason); }
    });
}

function handleAddFriendRequest(resp) { addFriendRequestUI(resp.fromUserId, resp.fromUserName, resp.reason); }

function addFriendRequestUI(fromUserId, fromUserName, reason) {
    let sl = document.querySelector('#session-list');
    let li = document.createElement('li');
    li.setAttribute('from-user-id', fromUserId);
    li.style.cssText = 'height:auto;min-height:70px;flex-direction:column;align-items:stretch;padding:14px 20px';

    let top = document.createElement('div');
    top.style.cssText = 'display:flex;align-items:center';
    let av = document.createElement('div');
    av.className = 'item-avatar ' + getAvatarColor(fromUserName);
    av.style.cssText = 'width:36px;height:36px;font-size:14px;margin-right:10px';
    av.innerHTML = getAvatarText(fromUserName);
    let info = document.createElement('div');
    info.style.flex = '1';
    info.innerHTML = '<h3 style="font-size:14px;color:#fff;margin-bottom:2px">' + fromUserName + ' 请求添加好友</h3>'
        + '<p style="font-size:12px;color:#b0b0c8">' + escapeHtml(reason) + '</p>';
    top.appendChild(av); top.appendChild(info);

    let actions = document.createElement('div');
    actions.className = 'friend-request-actions';
    actions.style.cssText = 'margin-top:10px;padding-left:46px';

    let btnA = document.createElement('button');
    btnA.className = 'btn-accept'; btnA.innerHTML = '接受';
    btnA.onclick = function(e) {
        e.stopPropagation();
        $.ajax({
            type: 'get', url: '/acceptFriend?friendId=' + fromUserId,
            success: function() { alert("已通过 " + fromUserName + " 的好友申请！"); getFriendList(); sl.removeChild(li); },
            error: function() { alert("处理失败！"); }
        });
    };

    let btnR = document.createElement('button');
    btnR.className = 'btn-reject'; btnR.innerHTML = '拒绝';
    btnR.onclick = function(e) {
        e.stopPropagation();
        $.ajax({
            type: 'get', url: '/rejectFriend?friendId=' + fromUserId,
            success: function() { alert("已拒绝 " + fromUserName + " 的好友申请。"); sl.removeChild(li); },
            error: function() { alert("处理失败！"); }
        });
    };

    actions.appendChild(btnA); actions.appendChild(btnR);
    li.appendChild(top); li.appendChild(actions);
    sl.insertBefore(li, sl.children[0]);
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
