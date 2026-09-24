/**
 * 网页聊天室 - 客户端主逻辑
 *
 * 功能分区（按编号找）：
 *   0   公共工具（转义 / 时间 / 头像）
 *   1   获取用户信息 + 更换头像
 *   2   标签页切换
 *   3   好友列表
 *   4   会话列表（单聊 + 群聊）
 *   5   点击会话
 *   6   点击好友 → 创建/切换会话
 *   6.5 建群 / 邀请入群（多选模式）
 *   7   获取历史消息
 *   8   添加消息到界面（含图片消息）
 *   9   WebSocket（断线自动重连 + 应用层心跳）
 *   10  发送消息（文本 / 图片 / 表情）
 *   11  处理实时推送
 *   12  搜索用户
 *   13  好友请求
 *   14  搜索聊天记录
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

// 单聊 / 群聊的会话类型，和后端 Constant.SESSION_TYPE_* 对齐
const SESSION_TYPE_SINGLE = 1;
const SESSION_TYPE_GROUP = 2;

// 消息类型，和后端 Constant.MSG_TYPE_* 对齐
const MSG_TYPE_IMAGE = 2;

const avatarColors = ['avatar-green','avatar-blue','avatar-purple','avatar-orange','avatar-pink','avatar-teal'];

function getAvatarColor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
    return avatarColors[Math.abs(hash) % avatarColors.length];
}

function getAvatarText(name) { return name ? name.charAt(0).toUpperCase() : '?'; }

function escapeHtml(text) {
    let div = document.createElement('div');
    div.appendChild(document.createTextNode(text == null ? '' : text));
    return div.innerHTML;
}

// 拼一个内联图标。图标本体定义在 client.html 顶部的 <svg class="sprite"> 里，
// 这里只负责引用 —— 用 id 引用而不是把 path 抄一遍，改图标只用改一处。
// 参数都是代码里写死的常量，不来自用户输入，所以直接拼 innerHTML 是安全的，
// 不需要过 escapeHtml。
function iconSvg(id, extraClass) {
    return '<svg class="icon' + (extraClass ? ' ' + extraClass : '') + '" aria-hidden="true">'
        + '<use href="#' + id + '"/></svg>';
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

// ====== 0.1 时间解析与撤回窗口 ======
// 必须和后端 Constant.REVOKE_WINDOW_MINUTES 保持一致。
// 前端这个值只决定"要不要显示撤回按钮"，真正说了算的是后端。
const REVOKE_WINDOW_MS = 2 * 60 * 1000;

// 后端给的是 "yyyy-MM-dd HH:mm:ss"。
// 千万别写 new Date(timeStr)：带空格的格式在 Safari 和部分内核上会得到 Invalid Date，
// 必须自己拆开喂给 new Date(y, m-1, d, H, M, S)。
function parseServerTime(timeStr) {
    if (!timeStr) return null;
    let parts = timeStr.split(' ');
    if (parts.length < 2) return null;
    let d = parts[0].split('-'), t = parts[1].split(':');
    if (d.length < 3 || t.length < 2) return null;
    return new Date(+d[0], +d[1] - 1, +d[2], +t[0], +t[1], +(t[2] || 0));
}

function withinRevokeWindow(postTime) {
    let t = parseServerTime(postTime);
    if (!t) return false;
    return Date.now() - t.getTime() <= REVOKE_WINDOW_MS;
}

// ====== 0.2 头像渲染 ======
// 思路：先铺一层"首字母色块"，如果有 userId 就再叠一张真实头像图。
// 服务端没设过头像会返回 404，img.onerror 把图自己删掉，色块自然露出来 ——
// 不用判断"有没有头像"，也不用任何额外的接口字段。
function setAvatarColor(el, name) {
    for (let c of avatarColors) el.classList.remove(c);
    el.classList.add(getAvatarColor(name || '?'));
}

function renderAvatar(el, name, userId) {
    if (!el) return;
    el.innerHTML = escapeHtml(getAvatarText(name));
    setAvatarColor(el, name);
    if (!userId) return;

    // 挂 user-id 是为了换完头像后能一次性找到界面上所有"我自己"的头像重新加载
    el.setAttribute('user-id', userId);
    let img = document.createElement('img');
    img.className = 'avatar-img';
    img.alt = '';
    img.onerror = function() { img.remove(); };
    img.src = '/avatar/' + userId;
    el.appendChild(img);
}

// 群头像没有图片可加载，直接用"多人"图标占位
function renderGroupAvatar(el, groupName) {
    if (!el) return;
    el.removeAttribute('user-id');
    el.innerHTML = iconSvg('i-users');
    setAvatarColor(el, groupName || 'group');
}

// 判断一条消息是不是图片。
// WS 推送里信封的 type 是字符串 "message"，真正的消息类型在 contentType；
// 历史消息接口返回的字段名是 type。两边都要认，且 contentType 优先。
function isImageMessage(msg) {
    if (msg && msg.contentType != null) return msg.contentType === MSG_TYPE_IMAGE;
    return msg && msg.type === MSG_TYPE_IMAGE;
}

// ====== 1. 获取用户信息 ======
function getUserInfo() {
    $.ajax({
        type: 'get', url: '/user/userInfo',
        success: function(body) {
            if (body && body.userId > 0) {
                selfUserId = body.userId;
                selfUsername = body.username;
                document.querySelector('#user-name').innerHTML = escapeHtml(body.username);
                renderAvatar(document.querySelector('#user-avatar'), body.username, body.userId);
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

// ====== 1.1 更换头像 ======
function initAvatarUpload() {
    let av = document.querySelector('#user-avatar');
    let fileInput = document.querySelector('#avatar-file');
    av.onclick = function() {
        // 每次先清空，否则连续选同一个文件不会触发 change
        fileInput.value = '';
        fileInput.click();
    };
    fileInput.onchange = function() {
        let file = fileInput.files[0];
        if (!file) return;
        if (file.size > 5 * 1024 * 1024) { alert('图片太大了，最多 5MB'); return; }

        let fd = new FormData();
        fd.append('file', file);
        // 关键的三个设置：processData/contentType 必须关掉，让浏览器自己带 multipart 边界。
        // 关掉 contentType 后 jQuery 也不会覆盖 User-Token 头，鉴权正常。
        $.ajax({
            type: 'post', url: '/user/avatar',
            data: fd, processData: false, contentType: false,
            success: function() {
                // 头像 URL 还是 /avatar/4 没变，浏览器会拿缓存里的旧图。
                // 所以带个时间戳参数强制重新下载，并把界面上所有自己的头像一起刷新。
                refreshAvatars(selfUserId);
            },
            error: function(xhr) {
                if (xhr.status !== 401) {
                    alert(xhr.responseJSON ? xhr.responseJSON.message : '头像上传失败');
                }
            }
        });
    };
}

// 换完头像后，把所有显示"这个 userId"的头像重新拉一次
function refreshAvatars(userId) {
    if (!userId) return;
    let bust = '?t=' + Date.now();
    for (let el of document.querySelectorAll('[user-id="' + userId + '"]')) {
        let old = el.querySelector('.avatar-img');
        if (old) old.remove();
        let img = document.createElement('img');
        img.className = 'avatar-img';
        img.alt = '';
        img.onerror = function() { img.remove(); };
        img.src = '/avatar/' + userId + bust;
        el.appendChild(img);
    }
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
                av.className = 'item-avatar';
                renderAvatar(av, f.friendName, f.friendId);
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
// onDone 可选：列表 DOM 重建完成后的回调（建群成功后要立刻点开新群，就靠它）
function getSessionList(onDone) {
    // 记住当前选中的会话，重建后恢复高亮。
    // 不这么做的话，任何一次列表刷新都会把"正在聊的会话"丢掉，
    // 用户接着点发送就会提示"请先选择会话"。
    let sel = document.querySelector('#session-list>.selected');
    let keepId = sel ? sel.getAttribute('message-session-id') : null;

    $.ajax({
        type: 'get', url: '/sessionList',
        success: function(body) {
            let sl = document.querySelector('#session-list');
            sl.innerHTML = '';
            for (let s of body) {
                sl.appendChild(buildSessionItem(s));
            }
            if (keepId) {
                let li = findSessionById(keepId);
                if (li) li.classList.add('selected');
            }
            if (typeof onDone === 'function') onDone();
        }
    });
}

function buildSessionItem(s) {
    let isGroup = (s.sessionType === SESSION_TYPE_GROUP);
    let friend = (s.friends && s.friends.length > 0) ? s.friends[0] : null;
    // 单聊标题是对方昵称；群聊标题是群名，没有群名就兜个"群聊"
    let title = isGroup ? (s.sessionName || '群聊') : (friend ? friend.friendName : '未知会话');

    // 摘要：群聊要带上"谁说的"，否则群里几个人说话分不清谁是谁
    let lm = s.lastMessage || '';
    if (isGroup && s.lastMessageFrom && lm) lm = s.lastMessageFrom + ': ' + lm;
    if (lm.length > 15) lm = lm.substring(0, 15) + '...';

    let li = document.createElement('li');
    li.setAttribute('message-session-id', s.sessionId);
    li.setAttribute('session-type', isGroup ? SESSION_TYPE_GROUP : SESSION_TYPE_SINGLE);

    let av = document.createElement('div');
    av.className = 'item-avatar';
    let ct = document.createElement('div');
    ct.className = 'item-content';
    ct.innerHTML = '<h3>' + escapeHtml(title) + '</h3><p>' + escapeHtml(lm) + '</p>';

    if (isGroup) {
        renderGroupAvatar(av, title);
        li.setAttribute('member-count', s.memberCount || 0);
        // 把群成员（不含自己）挂到属性上，"邀请入群"时用来判断谁已经在群里了
        li.setAttribute('member-ids', (s.friends || []).map(function(f) { return f.friendId; }).join(','));
    } else {
        renderAvatar(av, friend ? friend.friendName : '?', friend ? friend.friendId : null);
    }

    li.appendChild(av); li.appendChild(ct);
    setUnreadBadge(li, s.unreadCount);
    li.onclick = function() { clickSession(li); };
    return li;
}

// ====== 5. 点击会话 ======
function clickSession(li) {
    let all = document.querySelectorAll('#session-list>li');
    for (let s of all) { if (s === li) s.classList.add('selected'); else s.classList.remove('selected'); }
    let sessionId = li.getAttribute('message-session-id');

    // 移动端：先切到聊天视图（整屏滑进来），再拉历史消息。
    // 桌面端这个函数第一行就 return，什么也不做。
    // 放在这里是因为 clickSession 是"打开会话"的唯一汇合点：
    //   会话列表点 → clickSession；好友列表点 → sessionLi.click()；
    //   聊天记录搜索结果点 → openSessionById → li.click()。
    openChat();

    // 乐观更新：先把红点清掉，别让用户看到"自己正盯着的会话还有未读"
    clearUnreadBadge(li);
    // 再通知后端把已读游标推上去
    markSessionRead(sessionId);

    // 这一步会把 #message-show 清空再填，所以上面先切视图不会看到上一个会话的残留消息
    getHistoryMessage(sessionId);
}

// 按 sessionId 打开会话（搜索结果点进来用）。
// 列表里找不到就先把列表拉一遍 —— 刚被拉进群时本地还没有这个会话。
function openSessionById(sessionId) {
    let li = findSessionById(sessionId);
    if (li) {
        document.querySelector('#tab-session').click();
        li.click();
        return;
    }
    getSessionList(function() {
        let again = findSessionById(sessionId);
        if (again) {
            document.querySelector('#tab-session').click();
            again.click();
        } else {
            alert('找不到这个会话，可能已经退出了');
        }
    });
}

// ====== 6. 点击好友 → 创建/切换会话 ======
function clickFriend(friend, friendLi) {
    // 多选模式下点击只是"勾选"，不开聊天窗口
    if (friendSelectMode) { toggleFriendSelection(friend, friendLi); return; }

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
        sessionLi.setAttribute('session-type', SESSION_TYPE_SINGLE);
        let av = document.createElement('div');
        av.className = 'item-avatar';
        renderAvatar(av, friend.friendName, friend.friendId);
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

// 只找"单聊"会话。群聊名字可能和某人重名，不排除的话会把群当成私聊点开。
function findSessionByName(name) {
    for (let li of document.querySelectorAll('#session-list>li')) {
        if (li.getAttribute('session-type') === String(SESSION_TYPE_GROUP)) continue;
        let h3 = li.querySelector('h3');
        if (h3 && name === h3.textContent.trim()) return li;
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

// ====== 6.5 建群 / 邀请入群 ======
// friendSelectMode: null = 普通模式；'create' = 建群；'invite' = 往已有群里拉人
let friendSelectMode = null;
let inviteSessionId = null;
let selectedFriendIds = [];

function initGroupCreate() {
    document.querySelector('#group-create-btn').onclick = function() {
        enterSelectMode('create', null);
    };
    document.querySelector('#group-cancel-btn').onclick = function() {
        exitSelectMode();
    };
    document.querySelector('#group-confirm-btn').onclick = function() {
        if (friendSelectMode === 'invite') doInviteMembers();
        else doCreateGroup();
    };
}

function enterSelectMode(mode, sessionId) {
    friendSelectMode = mode;
    inviteSessionId = sessionId;
    selectedFriendIds = [];

    document.querySelector('#friend-panel').classList.add('selecting');
    document.querySelector('#group-bar').classList.remove('hide');
    // 邀请入群时群名早就有了，不该让用户填
    let nameInput = document.querySelector('#group-name-input');
    if (mode === 'invite') nameInput.classList.add('hide');
    else nameInput.classList.remove('hide');
    document.querySelector('#group-confirm-btn').innerHTML = (mode === 'invite') ? '邀请' : '创建';

    // 先清掉旧勾选状态
    for (let li of document.querySelectorAll('#friend-list>li')) {
        li.classList.remove('picked');
    }
    updateSelectedTip();
    document.querySelector('#tab-friend').click();
}

function exitSelectMode() {
    friendSelectMode = null;
    inviteSessionId = null;
    selectedFriendIds = [];
    document.querySelector('#friend-panel').classList.remove('selecting');
    document.querySelector('#group-bar').classList.add('hide');
    document.querySelector('#group-name-input').value = '';
    for (let li of document.querySelectorAll('#friend-list>li')) {
        li.classList.remove('picked');
    }
}

function toggleFriendSelection(friend, li) {
    let fid = friend.friendId;

    // 邀请模式下，已经在群里的人直接拦掉，省得白跑一趟接口
    if (friendSelectMode === 'invite') {
        let groupLi = findSessionById(inviteSessionId);
        let exist = groupLi ? (groupLi.getAttribute('member-ids') || '') : '';
        let existIds = exist ? exist.split(',') : [];
        if (existIds.indexOf(String(fid)) > -1) {
            alert(friend.friendName + ' 已经在群里了');
            return;
        }
    }

    let idx = selectedFriendIds.indexOf(fid);
    if (idx > -1) {
        selectedFriendIds.splice(idx, 1);
        li.classList.remove('picked');
    } else {
        selectedFriendIds.push(fid);
        li.classList.add('picked');
    }
    updateSelectedTip();
}

function updateSelectedTip() {
    document.querySelector('#group-selected-tip').innerHTML = '已选 ' + selectedFriendIds.length + ' 人';
}

function doCreateGroup() {
    let name = document.querySelector('#group-name-input').value.trim();
    if (!name) { alert('请填写群名称'); return; }
    if (selectedFriendIds.length === 0) { alert('请至少选择一位好友'); return; }

    // memberIds 是数组，query 里用重复参数名，Spring 直接能绑到 List<Integer>
    let qs = selectedFriendIds.map(function(id) { return 'memberIds=' + id; }).join('&');
    $.ajax({
        type: 'post', url: '/group?name=' + encodeURIComponent(name) + '&' + qs,
        success: function(body) {
            let newId = body.sessionId;
            exitSelectMode();
            getSessionList(function() {
                // 建完直接把新群打开，用户不用自己再去列表里找
                openSessionById(newId);
            });
        },
        error: function(xhr) {
            if (xhr.status !== 401) {
                alert(xhr.responseJSON ? xhr.responseJSON.message : '创建群聊失败');
            }
        }
    });
}

// 逐个调接口，串行执行。
// 用串行而不是并发：接口返回的错误原因要能对应到具体是谁，
// 并发时多条失败提示会一起弹出来，分不清哪条是哪条。
function doInviteMembers() {
    if (!inviteSessionId) { alert('没有选择群聊'); return; }
    if (selectedFriendIds.length === 0) { alert('请至少选择一位好友'); return; }

    let ids = selectedFriendIds.slice();
    let sessionId = inviteSessionId;
    let failed = [];
    let i = 0;

    exitSelectMode();

    function next() {
        if (i >= ids.length) {
            if (failed.length > 0) alert('部分邀请失败：\n' + failed.join('\n'));
            else alert('邀请成功！');
            getSessionList();
            return;
        }
        let id = ids[i++];
        $.ajax({
            type: 'post', url: '/groupMember?sessionId=' + sessionId + '&newMemberId=' + id,
            success: next,
            error: function(xhr) {
                if (xhr.status !== 401) {
                    failed.push((xhr.responseJSON ? xhr.responseJSON.message : '邀请失败'));
                }
                next();
            }
        });
    }
    next();
}

function quitGroup(sessionId) {
    if (!confirm('确定要退出这个群聊吗？')) return;
    $.ajax({
        type: 'post', url: '/quitGroup?sessionId=' + sessionId,
        success: function() {
            // 退群后这个会话就不该出现在列表里了，直接重新拉一次
            getSessionList();
            document.querySelector('#chat-title').innerHTML = '选择一个会话开始聊天';
            document.querySelector('#message-show').innerHTML =
                '<div class="empty-state"><div class="empty-icon">' + iconSvg('i-chat', 'icon-lg')
                + '</div><p>已退出群聊</p></div>';
            document.querySelector('#title-actions').innerHTML = '';
        },
        error: function(xhr) {
            if (xhr.status !== 401) {
                alert(xhr.responseJSON ? xhr.responseJSON.message : '退群失败');
            }
        }
    });
}

// ====== 7. 获取历史消息 ======
function getHistoryMessage(sessionId) {
    let titleDiv = document.querySelector('#chat-title');
    let actions = document.querySelector('#title-actions');
    let msgDiv = document.querySelector('#message-show');
    msgDiv.innerHTML = '';
    actions.innerHTML = '';

    let sel = document.querySelector('#session-list>.selected');
    let isGroup = sel && sel.getAttribute('session-type') === String(SESSION_TYPE_GROUP);
    if (sel) {
        let h3 = sel.querySelector('h3');
        let name = h3 ? h3.textContent.trim() : '聊天';
        if (isGroup) {
            let count = sel.getAttribute('member-count') || '?';
            titleDiv.innerHTML = escapeHtml(name) + ' <span class="member-count">(' + count + '人)</span>';
            // 群聊才给"邀请 / 退出"，单聊没有这两个操作
            actions.innerHTML = '<button class="title-btn" id="invite-btn">+ 邀请</button>'
                + '<button class="title-btn danger" id="quit-group-btn">退出群聊</button>';
            document.querySelector('#invite-btn').onclick = function() {
                enterSelectMode('invite', sessionId);
            };
            document.querySelector('#quit-group-btn').onclick = function() {
                quitGroup(sessionId);
            };
        } else {
            titleDiv.innerHTML = escapeHtml(name);
        }
    }

    if (!sessionId) {
        msgDiv.innerHTML = '<div class="empty-state"><div class="empty-icon">' + iconSvg('i-chat', 'icon-lg')
            + '</div><p>开始新的对话吧</p></div>';
        return;
    }

    // 单聊不必每条消息都写一遍发送者名字（对面就一个人），群聊才需要区分是谁说的。
    // 只有"明确读到这是单聊"才隐藏：sel 拿不到时保持显示，宁可多显示名字，
    // 也别在群聊里把人名弄丢。（class 的默认态就是显示，见 client.css 里的注释）
    msgDiv.classList.toggle('is-single', !!sel && !isGroup);

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
    // 优先用 fromId 判断"是不是我自己"：群聊里光比名字，
    // 万一两个用户重名就会把自己的消息渲染到左边。
    let isSelf = (msg.fromId != null) ? (msg.fromId === selfUserId) : (fromName === selfUsername);

    // 已撤回的消息不走气泡，渲染成一条居中的灰色提示
    if (msg.revoked) {
        container.appendChild(buildRevokedNode(msg.messageId, isSelf ? '你' : fromName));
        return;
    }

    let div = document.createElement('div');
    div.className = 'message ' + (isSelf ? 'message-right' : 'message-left');
    // 撤回推送要靠这个属性找到界面上对应的那条消息
    div.setAttribute('message-id', msg.messageId);

    let av = document.createElement('div');
    av.className = 'msg-avatar';
    renderAvatar(av, fromName, msg.fromId);

    let bubble = document.createElement('div');
    bubble.className = 'bubble';

    // 图片消息渲染成 <img>，文本消息才走转义后的纯文本。
    // content 是后端给的 /upload/... 路径（后端还校验过前缀），
    // 所以这里能安全地当 src 用，不像自由文本那样有注入风险。
    let body;
    if (isImageMessage(msg)) {
        body = '<img class="msg-image" src="' + escapeHtml(msg.content || '') + '" alt="图片"'
            + ' onclick="window.open(this.src)">';
    } else {
        body = '<div class="msg-content">' + escapeHtml(msg.content || '') + '</div>';
    }

    bubble.innerHTML = '<div class="sender-info">' + escapeHtml(fromName) + '</div>'
        + body
        + '<div class="msg-time">' + formatTime(msg.postTime) + '</div>';

    div.appendChild(av);
    div.appendChild(bubble);

    // 自己发的、且还在撤回窗口内，才给撤回按钮。
    // 别人的消息不加 —— 但这也只是"不给按钮"，后端一样会拦（前端判断永远不作数）。
    // 页面停留久了时间会流逝，按钮还在也能点，点了后端会拒并返回原因。
    if (isSelf && withinRevokeWindow(msg.postTime)) {
        let btn = document.createElement('button');
        btn.className = 'revoke-btn';
        btn.innerHTML = '撤回';
        btn.onclick = function() { revokeMessage(msg.messageId, btn); };
        div.appendChild(btn);
    }

    container.appendChild(div);
}

// 已撤回消息的占位节点
function buildRevokedNode(messageId, who) {
    let div = document.createElement('div');
    div.className = 'message-revoked';
    div.setAttribute('message-id', messageId);
    div.innerHTML = escapeHtml(who) + ' 撤回了一条消息';
    return div;
}

// ====== 9. WebSocket（断线自动重连 + 应用层心跳）======
// 为什么要自己做这两件事：
//   1) 浏览器原生 WebSocket 不暴露 ping API，拿不到底层 ping/pong 帧，只能在应用层自制一对
//      （发 {type:'ping'}，服务端回 {type:'pong'}）；
//   2) 网络"半开"（拔网线 / 笔记本睡眠 / 4G 切 WiFi）时 TCP 不会立刻报错，
//      onclose 可能几分钟都不触发，而界面早就收不到任何推送了 —— 靠心跳超时兜底；
//   3) 中间的反向代理普遍"60s 无流量就掐连接"，心跳顺便把连接喂饱。
//
// 状态文案和 client.css 的 .chat-status.online / .offline 一一对应，改文案时一起看。
const WS_HEARTBEAT_MS = 25 * 1000;      // 每 25s 发一次 ping
const WS_PONG_TIMEOUT_MS = 60 * 1000;   // 超过 60s 没收到服务端任何帧 → 判定连接已死
const WS_RECONNECT_BASE_MS = 1000;      // 首次重连等 1s
const WS_RECONNECT_MAX_MS = 30 * 1000;  // 指数退避上限 30s

let wsHeartbeatTimer = null;
let wsReconnectTimer = null;
let wsReconnectAttempt = 0;     // 连续失败次数，用来算退避时长
let wsLastAliveAt = 0;          // 最后一次收到服务端任何帧的时间
let wsClosedByUser = false;     // 主动关（关页面）之后不再重连
let wsConnectedOnce = false;    // 区分"首次连上"和"重连成功"，决定要不要补拉数据
let wsGlobalEventsBound = false;// 全局监听只绑一次，保证 initWebSocket 可以重复调

function initWebSocket() {
    wsClosedByUser = false;

    // 全局监听必须只绑一次。绑在每次调用里的话，谁多调一次 initWebSocket
    // 就多出一份监听 —— 切回前台会被触发 N 次，纯属埋雷。
    if (!wsGlobalEventsBound) {
        wsGlobalEventsBound = true;

        // 页面切回前台、网络恢复时立刻重连，不必干等退避计时器。
        // 笔记本合盖再打开是最典型的场景：后台标签页的定时器会被浏览器限流甚至冻结，
        // 光靠 setTimeout 那套退避可能要等很久才轮到。
        document.addEventListener('visibilitychange', function() {
            if (document.visibilityState === 'visible') reconnectNowIfNeeded();
        });
        window.addEventListener('online', reconnectNowIfNeeded);
        window.onbeforeunload = function() { closeWebSocket(); };
    }

    connectWebSocket();
}

function buildWsUrl() {
    // 协议和主机从当前页面取，避免端口写死（本地 8080 / 测试 18080 都能用）
    let proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    // 浏览器原生 WebSocket 不支持自定义请求头，User-Token 那套用不了，
    // token 只能挂在 URL query 上，由后端 AuthHandshakeInterceptor 解析。
    return proto + location.host + '/ws/message?token=' + encodeURIComponent(localStorage.getItem('token') || '');
}

function connectWebSocket() {
    if (wsClosedByUser) return;
    // 已经连着、或正在连，就别重复建 —— 否则会同时存在多条连接，
    // 服务端那边看着像"多标签页"，推送就重复了
    if (websocket && (websocket.readyState === WebSocket.OPEN || websocket.readyState === WebSocket.CONNECTING)) return;

    let wsUrl = buildWsUrl();
    websocket = new WebSocket(wsUrl);

    websocket.onopen = function() {
        console.log("WebSocket 连接成功: " + wsUrl);
        wsReconnectAttempt = 0;
        wsLastAliveAt = Date.now();
        setChatStatus('在线', 'online');
        startHeartbeat();

        // wsConnectedOnce 已经是 true，说明这一次是"重连"而不是首次连接
        if (wsConnectedOnce) resyncAfterReconnect();
        wsConnectedOnce = true;
    };

    websocket.onmessage = function(event) {
        // 收到任何帧都算"活着"，不用非得是 pong —— 别人发来的消息一样能证明连接没死
        wsLastAliveAt = Date.now();

        let resp;
        try {
            resp = JSON.parse(event.data);
        } catch (e) {
            console.warn('WebSocket 收到非 JSON 数据', event.data);
            return;
        }

        if (resp.type === 'pong') return;   // 心跳应答，没有业务含义
        if (resp.type === 'message') handleMessage(resp);
        else if (resp.type === 'addFriendRequest') handleAddFriendRequest(resp);
        else if (resp.type === 'acceptFriend') handleAcceptFriend(resp);
        else if (resp.type === 'revoke') handleRevoke(resp);
        else if (resp.type === 'groupCreated') handleGroupCreated(resp);
        else if (resp.type === 'error') alert(resp.content || '消息发送失败');
    };

    websocket.onclose = function() {
        stopHeartbeat();
        setChatStatus('离线', 'offline');
        scheduleReconnect();
    };

    websocket.onerror = function() { console.log("WebSocket 异常"); };
}

function scheduleReconnect() {
    if (wsClosedByUser) return;
    if (wsReconnectTimer) return;   // 已经排上队了，别叠第二份

    // 指数退避 + 随机抖动。抖动是为了多标签页同时掉线时错开重连，
    // 不然几个页面会在同一毫秒一起砸服务端（服务刚重启完最容易踩这个）。
    let delay = Math.min(WS_RECONNECT_BASE_MS * Math.pow(2, wsReconnectAttempt), WS_RECONNECT_MAX_MS);
    delay += Math.floor(Math.random() * 500);
    wsReconnectAttempt++;

    console.log('WebSocket ' + Math.round(delay / 1000) + 's 后重连（第 ' + wsReconnectAttempt + ' 次）');
    wsReconnectTimer = setTimeout(function() {
        wsReconnectTimer = null;
        probeTokenIfNeeded();
        // 只在这会儿确实没连上时才改文案，避免"其实已经连上、状态栏却写着重连中"
        if (!isWsOpen()) setChatStatus('重连中…', 'offline');
        connectWebSocket();
    }, delay);
}

// token 过期时握手会被服务端直接拒掉，重试一万次也没用。
// 但握手阶段失败浏览器只给一个 close code 1006，跟"网络不通"完全区分不出来，
// 所以退而求其次：每连续失败 3 次探一次 /user/userInfo ——
// 401 会被 document 上那个全局 ajaxError 接走，清 token 并跳登录页。
function probeTokenIfNeeded() {
    if (wsReconnectAttempt === 0 || wsReconnectAttempt % 3 !== 0) return;
    $.ajax({ type: 'get', url: '/user/userInfo', data: { t: Date.now() } });
}

function reconnectNowIfNeeded() {
    if (wsClosedByUser) return;
    if (isWsOpen() || (websocket && websocket.readyState === WebSocket.CONNECTING)) return;
    if (wsReconnectTimer) { clearTimeout(wsReconnectTimer); wsReconnectTimer = null; }
    wsReconnectAttempt = 0;   // 人已经回到页面了，别让他等退避，退避从头算
    connectWebSocket();
}

function startHeartbeat() {
    stopHeartbeat();
    wsHeartbeatTimer = setInterval(function() {
        if (!isWsOpen()) return;
        if (Date.now() - wsLastAliveAt > WS_PONG_TIMEOUT_MS) {
            // 半开连接：readyState 还是 OPEN、TCP 看着也"连着"，但对面早就不响应了。
            // 只有主动 close 才会触发 onclose → 走重连，
            // 否则这条连接会这么"假在线"挂上几个小时。
            console.log('WebSocket 心跳超时，主动断开触发重连');
            websocket.close();
            return;
        }
        websocket.send(JSON.stringify({ type: 'ping' }));
    }, WS_HEARTBEAT_MS);
}

function stopHeartbeat() {
    if (wsHeartbeatTimer) { clearInterval(wsHeartbeatTimer); wsHeartbeatTimer = null; }
}

// 主动断开：关页面时调，之后不再重连
function closeWebSocket() {
    wsClosedByUser = true;
    stopHeartbeat();
    if (wsReconnectTimer) { clearTimeout(wsReconnectTimer); wsReconnectTimer = null; }
    if (websocket) websocket.close();
}

function setChatStatus(text, cls) {
    let el = document.querySelector('#chat-status');
    if (!el) return;
    el.innerHTML = text;
    el.className = cls ? 'chat-status ' + cls : 'chat-status';
}

// 重连成功后补拉数据。
// 断线期间的推送是全丢的：别人发的消息、撤回、被拉进群、好友申请，一条都没收到。
// 所以不能"连上就当无事发生"，必须重新拉会话列表 + 当前会话的历史消息，把缺的补上。
// 历史消息接口是全量返回的（还没做分页），重拉一遍正好补齐；
// getHistoryMessage 会先 innerHTML='' 清空再渲染，不会出现重复气泡。
function resyncAfterReconnect() {
    getSessionList(function() {
        let sel = document.querySelector('#session-list>.selected');
        if (sel) getHistoryMessage(sel.getAttribute('message-session-id'));
    });
    getAddFriendRequest();
}

// ====== 10. 发送消息 ======
function initSendButton() {
    let sendBtn = document.querySelector('#send-btn');
    let input = document.querySelector('#message-textarea');
    sendBtn.onclick = function() { sendMessage(); };
    input.addEventListener('keydown', function(e) {
        if (e.key !== 'Enter' || e.shiftKey) return;
        // 🔴 触屏设备上回车键就是"换行"，不能拿来当发送 ——
        // 否则用户想换行、结果整句话直接发出去了。触屏只能点右下角的「发送」。
        // 每次按键现算而不是启动时算一次：横竖屏切换、临时接外接键盘都会改变结果。
        if (isTouchDevice()) return;
        e.preventDefault();
        sendMessage();
    });
}

// 取当前选中的会话 id，没有就返回 null（顺带把提示打出来）
function currentSessionId() {
    let sel = document.querySelector('#session-list>.selected');
    if (!sel) { alert('请先选择会话！'); return null; }
    let sid = sel.getAttribute('message-session-id');
    if (!sid) { alert('会话未创建成功，请稍后重试！'); return null; }
    return sid;
}

function isWsOpen() {
    return websocket && websocket.readyState === WebSocket.OPEN;
}

function sendMessage() {
    let input = document.querySelector('#message-textarea');
    let content = input.value.trim();
    if (!content) return;
    let sid = currentSessionId();
    if (!sid) return;
    if (!isWsOpen()) {
        // 用户这一下点击就是"我现在要用"，别让他干等退避计时器，立刻抢一次重连
        reconnectNowIfNeeded();
        alert('连接已断开，正在重连，请稍后重试');
        return;
    }
    websocket.send(JSON.stringify({ type: 'message', sessionId: parseInt(sid), content: content }));
    input.value = '';
}

// ====== 10.1 发送图片 ======
// 两步走：先 POST /message/image 把文件存下来拿到路径，
// 再通过 WebSocket 发一条 contentType=2 的消息。
// 这么拆的好处是上传被拒时能立刻弹原因（格式不对、超过 5MB），
// 不用等 WebSocket 那条链路绕一圈回来。
function initImageSend() {
    let btn = document.querySelector('#image-btn');
    let fileInput = document.querySelector('#chat-image-file');
    btn.onclick = function() {
        let sid = currentSessionId();
        if (!sid) return;
        if (!isWsOpen()) {
        // 用户这一下点击就是"我现在要用"，别让他干等退避计时器，立刻抢一次重连
        reconnectNowIfNeeded();
        alert('连接已断开，正在重连，请稍后重试');
        return;
    }
        fileInput.value = '';
        fileInput.click();
    };
    fileInput.onchange = function() {
        let file = fileInput.files[0];
        if (file) uploadAndSendImage(file);
    };
}

function uploadAndSendImage(file) {
    if (file.size > 5 * 1024 * 1024) { alert('图片太大了，最多 5MB'); return; }
    let sid = currentSessionId();
    if (!sid) return;

    let fd = new FormData();
    fd.append('file', file);
    $.ajax({
        type: 'post', url: '/message/image',
        data: fd, processData: false, contentType: false,
        success: function(body) {
            if (!isWsOpen()) { alert('连接已断开，图片已上传但没发出去'); return; }
            websocket.send(JSON.stringify({
                type: 'message',
                sessionId: parseInt(sid),
                content: body.url,
                contentType: MSG_TYPE_IMAGE
            }));
        },
        error: function(xhr) {
            if (xhr.status !== 401) {
                alert(xhr.responseJSON ? xhr.responseJSON.message : '图片上传失败');
            }
        }
    });
}

// ====== 10.2 表情面板 ======
const EMOJIS = [
    '\u{1F600}', '\u{1F601}', '\u{1F602}', '\u{1F923}', '\u{1F60A}', '\u{1F60D}',
    '\u{1F618}', '\u{1F61C}', '\u{1F914}', '\u{1F610}', '\u{1F62D}', '\u{1F621}',
    '\u{1F44D}', '\u{1F44E}', '\u{1F44C}', '\u{1F64F}', '\u{1F44F}', '\u{1F4AA}',
    '\u{1F389}', '\u{1F525}', '\u{2764}\u{FE0F}', '\u{1F494}', '\u{1F339}', '\u{1F340}',
    '\u{2B50}', '\u{2600}\u{FE0F}', '\u{1F308}', '\u{1F37A}', '\u{2615}', '\u{1F381}',
    '\u{1F4B0}', '\u{1F382}', '\u{1F436}', '\u{1F431}', '\u{1F338}', '\u{1F34E}',
    '\u{26BD}', '\u{1F3B5}', '\u{1F4F7}', '\u{2708}\u{FE0F}', '\u{1F697}', '\u{1F4A4}',
    '\u{2705}', '\u{274C}', '\u{2753}', '\u{2757}', '\u{1F60E}', '\u{1F973}'
];

function initEmoji() {
    let panel = document.querySelector('#emoji-panel');
    let btn = document.querySelector('#emoji-btn');
    panel.innerHTML = '';
    for (let e of EMOJIS) {
        let s = document.createElement('span');
        s.className = 'emoji-item';
        s.textContent = e;
        s.onclick = function() {
            let ta = document.querySelector('#message-textarea');
            ta.value += e;
            ta.focus();
        };
        panel.appendChild(s);
    }
    // stopPropagation 不能省：不然这个点击会冒泡到下面的 document 监听，
    // 面板刚打开就被立刻关掉。
    btn.onclick = function(ev) {
        ev.stopPropagation();
        panel.classList.toggle('hide');
    };
    // 点面板以外的任何地方收起
    document.addEventListener('click', function(ev) {
        if (panel.classList.contains('hide')) return;
        if (panel.contains(ev.target) || btn.contains(ev.target)) return;
        panel.classList.add('hide');
    });
}

// ====== 11. 处理实时消息 ======
function handleMessage(resp) {
    let fromName = resp.fromName || '未知用户';
    let sl = document.querySelector('#session-list');
    let cur = findSessionById(resp.sessionId);

    if (!cur) {
        // 本地列表里没有这个会话，说明列表是旧的（典型场景：刚被别人拉进群）。
        // 直接重新拉一次列表，比在这里手工拼一个字段残缺的会话项可靠。
        getSessionList();
        return;
    }

    // 摘要：图片显示占位文本，文本正常截断
    let p = cur.querySelector('p');
    if (p) {
        let pv = isImageMessage(resp) ? '[图片]' : (resp.content || '');
        if (pv.length > 15) pv = pv.substring(0, 15) + '...';
        p.innerHTML = escapeHtml(pv);
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

// ====== 11.1 撤回消息 ======
function revokeMessage(messageId, btn) {
    if (!confirm('确定要撤回这条消息吗？')) return;
    if (btn) btn.disabled = true;
    $.ajax({
        type: 'get', url: '/revokeMessage?messageId=' + messageId,
        success: function() {
            // 正常情况下界面由 WebSocket 广播来改。这里再兜一次，是因为 WS 断线重连
            // 需要时间（最少 1s），这段空窗里撤回广播收不到，
            // 用户点了撤回却毫无反应会以为失败。
            // markMessageRevoked 找不到节点就 return，所以重复调用无害。
            markMessageRevoked(document.querySelector('#message-show'), messageId, selfUsername, true);
        },
        error: function(xhr) {
            if (xhr.status !== 401) {
                alert(xhr.responseJSON ? xhr.responseJSON.message : '撤回失败');
            }
            // 失败要把按钮恢复，否则用户没法重试
            if (btn) btn.disabled = false;
        }
    });
}

// 把界面上某条消息换成"撤回了一条消息"。找不到就 return（幂等）——
// 撤回的是别的会话的消息时就会走到这里，静默忽略即可。
function markMessageRevoked(container, messageId, fromName, isSelf) {
    if (!container || !messageId) return;
    let old = container.querySelector('.message[message-id="' + messageId + '"]');
    if (!old) return;
    container.replaceChild(buildRevokedNode(messageId, isSelf ? '你' : (fromName || '未知用户')), old);
}

function handleRevoke(resp) {
    // 后端带了 fromId，优先用它判断（比名字可靠）
    let isSelf = (resp.fromId != null) ? (resp.fromId === selfUserId) : (resp.fromName === selfUsername);

    // 1. 消息区：把那条气泡换成提示
    markMessageRevoked(document.querySelector('#message-show'), resp.messageId, resp.fromName, isSelf);

    // 2. 会话列表的摘要。撤回按钮只对 2 分钟内的消息出现，而列表里的最后一条基本就是它，
    //    所以这里直接更新。要严格的话得重新拉 /sessionList，
    //    但 getSessionList 会重建整个列表 DOM、把当前选中的会话丢掉，不划算。
    let cur = findSessionById(resp.sessionId);
    if (cur) {
        let p = cur.querySelector('p');
        if (p) p.innerHTML = escapeHtml((isSelf ? '你' : (resp.fromName || '未知用户')) + ' 撤回了一条消息');
    }
}

// ====== 11.2 被拉进群 ======
function handleGroupCreated(resp) {
    // 会话内容（成员、未读数、群名）全都得从后端拿，所以直接重新拉列表。
    // 刚被拉进群，不自动打开聊天窗口 —— 那样太打扰，让用户自己去列表里点。
    getSessionList();
    alert('你已被拉入群聊：' + (resp.groupName || '新群聊'));
}

// ====== 12. 搜索好友 ======
function initFindFriend() {
    let btn = document.querySelector('#search-btn');
    let msgBtn = document.querySelector('#search-msg-btn');
    let input = document.querySelector('#search-input');
    btn.onclick = function() { doSearch(); };
    msgBtn.onclick = function() { doSearchMessage(); };
    input.addEventListener('keydown', function(e) { if (e.key === 'Enter') doSearch(); });
}

function doSearch() {
    let name = document.querySelector('#search-input').value.trim();
    if (!name) return;
    $.ajax({
        type: 'get', url: '/findFriend?name=' + encodeURIComponent(name),
        success: function(body) {
            document.querySelector('#chat-title').innerHTML = '查找结果';
            document.querySelector('#title-actions').innerHTML = '';
            let ms = document.querySelector('#message-show');
            ms.innerHTML = '';
            if (!body || body.length === 0) {
                ms.innerHTML = '<div class="empty-state"><div class="empty-icon">' + iconSvg('i-search', 'icon-lg')
                    + '</div><p>没有找到匹配的用户</p></div>';
                return;
            }
            for (let f of body) {
                let item = document.createElement('div');
                item.className = 'search-result-item';
                let av = document.createElement('div');
                av.className = 'msg-avatar';
                renderAvatar(av, f.friendName, f.friendId);
                let nm = document.createElement('span');
                nm.className = 'result-name';
                nm.innerHTML = escapeHtml(f.friendName);
                let ri = document.createElement('input');
                ri.type = 'text'; ri.className = 'result-reason'; ri.placeholder = '验证消息';
                let ab = document.createElement('button');
                ab.className = 'result-add-btn';
                ab.title = '发送好友请求';
                ab.innerHTML = iconSvg('i-user-plus', 'icon-sm');
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
            // 按钮切成"已发送"态：换成对勾 + 变灰，并且不能再点。
            // 颜色走 CSS 的 .done 类，不在这里写死色值（写死的话改配色就漏了这一处）。
            if (btn) {
                btn.innerHTML = iconSvg('i-check', 'icon-sm');
                btn.classList.add('done');
            }
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
    av.className = 'item-avatar';
    renderAvatar(av, fromUserName, fromUserId);
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

// ====== 14. 搜索聊天记录 ======
function doSearchMessage() {
    let kw = document.querySelector('#search-input').value.trim();
    if (!kw) { alert('请输入搜索内容'); return; }
    $.ajax({
        type: 'get', url: '/searchMessage?keyword=' + encodeURIComponent(kw),
        success: function(body) {
            document.querySelector('#chat-title').innerHTML = '聊天记录：' + escapeHtml(kw);
            document.querySelector('#title-actions').innerHTML = '';
            let ms = document.querySelector('#message-show');
            ms.innerHTML = '';
            if (!body || body.length === 0) {
                ms.innerHTML = '<div class="empty-state"><div class="empty-icon">' + iconSvg('i-clock', 'icon-lg')
                    + '</div><p>没有找到包含「' + escapeHtml(kw) + '」的消息</p></div>';
                return;
            }
            for (let m of body) ms.appendChild(buildSearchMessageItem(m, kw));
        }
    });
}

// 把关键字高亮出来。注意先转义再替换，两边都转义过了所以能对上。
function highlightKeyword(text, kw) {
    let safe = escapeHtml(text || '');
    let safeKw = escapeHtml(kw || '');
    if (!safeKw) return safe;
    return safe.split(safeKw).join('<mark>' + safeKw + '</mark>');
}

function buildSearchMessageItem(m, kw) {
    let isGroup = (m.sessionType === SESSION_TYPE_GROUP);
    let sessionName = m.sessionName || (isGroup ? '群聊' : '聊天');

    let item = document.createElement('div');
    item.className = 'search-msg-item';
    // 点一下跳到那条消息所在的会话
    item.onclick = function() { openSessionById(m.sessionId); };

    let head = document.createElement('div');
    head.className = 'search-msg-head';
    head.innerHTML = '<span class="search-msg-session">'
        + (isGroup ? '&#128101; ' : '&#128172; ') + escapeHtml(sessionName) + '</span>'
        + '<span class="search-msg-time">' + formatTime(m.postTime) + '</span>';

    let body = document.createElement('div');
    body.className = 'search-msg-body';
    body.innerHTML = '<span class="search-msg-from">' + escapeHtml(m.fromName || '未知用户') + '</span>：'
        + highlightKeyword(m.content, kw);

    item.appendChild(head); item.appendChild(body);
    return item;
}

// ====== 14. 移动端适配 ======
// 桌面是"侧栏 + 聊天区"并排；手机上宽度放不下，改成两个整屏视图叠着，靠 .chat-open 切换。
// 这里只负责"切视图"和触屏手势，具体长什么样全在 client.css 末尾的两个 @media 里。

// 和 client.css 的断点必须一致，改一处就要改另一处。
const MOBILE_BREAKPOINT = 768;

// 是不是触屏。用媒体查询特性来判断，不解析 UA 字符串：
// 带触摸屏的笔记本、iPad 外接鼠标键盘这些情况，UA 会骗人，hover/pointer 不会。
function isTouchDevice() {
    return window.matchMedia('(hover: none) and (pointer: coarse)').matches;
}

function isMobileLayout() {
    return window.innerWidth <= MOBILE_BREAKPOINT;
}

function isChatOpen() {
    let c = document.querySelector('.client-container');
    return !!c && c.classList.contains('chat-open');
}

function setChatOpen(open) {
    let c = document.querySelector('.client-container');
    if (c) { c.classList.toggle('chat-open', open); }
    if (open) { clearMessageActions(); }
}

// 由 clickSession 调用（那是"打开会话"的唯一汇合点）。
// 桌面端直接返回，所以桌面上的行为一点没变。
function openChat() {
    if (!isMobileLayout() || isChatOpen()) return;
    setChatOpen(true);
    // 压一条历史记录：这样手机的「返回」键是回列表，而不是直接退出页面。
    // 真正的收尾放在 popstate 里做 —— 让「点按钮」和「按返回键」共用同一条路。
    history.pushState({ chatroom: 'chat' }, '');
}

function initMobileNav() {
    let backBtn = document.querySelector('#back-btn');
    if (backBtn) {
        backBtn.onclick = function() {
            // 不直接 setChatOpen(false)，而是退回上一条历史。
            // 如果直接改类名，历史里那条 pushState 记录会一直留着，
            // 用户再按系统返回键就得多按一次才能退出页面。
            if (isChatOpen()) { history.back(); }
        };
    }

    window.addEventListener('popstate', function() {
        if (isChatOpen()) { setChatOpen(false); }
    });

    // 从手机尺寸拖回桌面尺寸时把 .chat-open 清掉，
    // 否则桌面下右侧面板会一直带着 translateX(100%) / 视差位移。
    window.addEventListener('resize', function() {
        if (!isMobileLayout() && isChatOpen()) { setChatOpen(false); }
    });
}

// ---- 虚拟键盘 ----
// iOS Safari 弹键盘时不会改变"布局视口"，而我们的布局是 height:100dvh + overflow:hidden，
// 页面不能滚动 → 输入框直接被键盘盖住。
// 解法：把容器高度钉到 visualViewport.height（"真正看得见的那块"），键盘一弹它就变小。
// Android Chrome 走的是另一条路（viewport meta 里的 interactive-widget=resizes-content），
// 两条都留着，互不冲突。
function initViewportFix() {
    let vv = window.visualViewport;
    if (!vv) { return; }
    let root = document.documentElement;
    let lastH = 0;

    let apply = function() {
        if (!isMobileLayout()) {
            root.style.removeProperty('--app-h');
            lastH = 0;
            return;
        }
        let h = Math.round(vv.height);
        // 高度没变就别动 —— 否则 visualViewport 的频繁事件会把用户手动滚到一半的位置反复顶到底
        if (h === lastH) { return; }
        lastH = h;
        root.style.setProperty('--app-h', h + 'px');
        // 键盘顶起来之后把消息区重新贴底
        let ms = document.querySelector('#message-show');
        if (ms) { scrollBottom(ms); }
    };

    vv.addEventListener('resize', apply);
    apply();
}

// ---- 长按气泡撤回 ----
// 触屏没有 hover，而 .revoke-btn 是 opacity:0 + :hover 才显示的 → 手机上永远露不出来。
// 做法不是另造一套菜单，只是给这条消息挂上 .show-actions，
// 让 client.css 里那条 `.message.show-actions > .revoke-btn { opacity: 1 }` 把它显出来。
const LONG_PRESS_MS = 500;

function clearMessageActions() {
    let opened = document.querySelectorAll('#message-show > .message.show-actions');
    for (let m of opened) { m.classList.remove('show-actions'); }
}

function initLongPressRevoke() {
    let box = document.querySelector('#message-show');
    if (!box) { return; }
    let timer = null, startX = 0, startY = 0;

    box.addEventListener('touchstart', function(e) {
        let msg = e.target.closest ? e.target.closest('.message') : null;
        // "有没有撤回按钮"就是准入条件：按钮是 addMessage 里按
        // 「自己发的 + 还在撤回窗口内」加的，这里不用重复判断一遍。
        // 不是可撤回的消息 → 顺手把上一条的菜单收掉。
        if (!msg || !msg.querySelector('.revoke-btn')) { clearMessageActions(); return; }

        let t = e.touches[0];
        startX = t.clientX;
        startY = t.clientY;
        timer = setTimeout(function() {
            timer = null;
            clearMessageActions();
            msg.classList.add('show-actions');
            // 有振动马达的设备给一下反馈；iOS Safari 不支持 vibrate，跳过即可
            if (navigator.vibrate) { navigator.vibrate(15); }
        }, LONG_PRESS_MS);
    }, { passive: true });

    // 手指一动就当成"在滚动列表"，取消长按 —— 否则滑消息会不停弹出撤回按钮
    box.addEventListener('touchmove', function(e) {
        if (!timer) { return; }
        let t = e.touches[0];
        if (Math.abs(t.clientX - startX) > 8 || Math.abs(t.clientY - startY) > 8) {
            clearTimeout(timer);
            timer = null;
        }
    }, { passive: true });

    box.addEventListener('touchend', function() {
        if (timer) { clearTimeout(timer); timer = null; }
    }, { passive: true });

    // 点别处收起已展开的菜单。
    // 注意：长按之后浏览器还会补一次 click，但那时 target 就在 .message.show-actions 里面，
    // 所以不会被这条规则误关。
    document.addEventListener('click', function(ev) {
        if (!ev.target.closest || !ev.target.closest('.message.show-actions')) {
            clearMessageActions();
        }
    });
}

// ====== 初始化 ======
getUserInfo();
initSwitchTab();
initAvatarUpload();
getFriendList();
getSessionList();
getAddFriendRequest();
initWebSocket();
initSendButton();
initImageSend();
initEmoji();
initFindFriend();
initGroupCreate();
initMobileNav();
initLongPressRevoke();
initViewportFix();
