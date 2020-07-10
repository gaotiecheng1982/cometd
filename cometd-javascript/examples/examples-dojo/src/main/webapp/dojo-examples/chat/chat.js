require(["dojo", "dojox/cometd", "dojox/cometd/timestamp", "dojox/cometd/ack", "dojox/cometd/reload"],
    function(dojo, cometd) {
        var stateKey = 'org.cometd.demo.state';
        var room = {
            _lastUser: null,
            _username: null,
            _connected: false,
            _disconnecting: false,
            _chatSubscription: null,
            _membersSubscription: null,

            _init: function() {
                dojo.removeClass("join", "hidden");
                dojo.addClass("joined", "hidden");
                dojo.byId('username').focus();

                dojo.query("#username").attr({
                    "autocomplete": "off"
                }).onkeyup(function(e) {
                    if (e.keyCode === dojo.keys.ENTER) {
                        room.join(dojo.byId('username').value);
                    }
                });

                dojo.query("#joinButton").onclick(function() {
                    room.join(dojo.byId('username').value);
                });

                dojo.query("#phrase").attr({
                    "autocomplete": "off"
                }).onkeyup(function(e) {
                    if (e.keyCode === dojo.keys.ENTER) {
                        room.chat();
                    }
                });

                dojo.query("#sendButton").onclick(function() {
                    room.chat();
                });

                dojo.query("#leaveButton").onclick(room, "leave");

                // Check if there was a saved application state.
                var stateItem = window.sessionStorage.getItem(stateKey);
                var state = stateItem ? JSON.parse(stateItem) : null;
                // Restore the state, if present.
                if (state) {
                    window.sessionStorage.removeItem(stateKey);
                    dojo.byId('username').value = state.username;
                    setTimeout(function() {
                        // This will perform the handshake
                        room.join(state.username);
                    }, 0);
                }
            },

            join: function(name) {
                room._disconnecting = false;

                if (name == null || name.length === 0) {
                    alert('Please enter a username');
                    return;
                }

                cometd.ackEnabled = dojo.byId("ackEnabled").checked;

                cometd.websocketEnabled = false;
                var cometdURL = location.protocol + "//" + location.host + config.contextPath + "/cometd";
                cometd.init({
                    url: cometdURL,
                    logLevel: "debug"
                });

                room._username = name;

                dojo.addClass("join", "hidden");
                dojo.removeClass("joined", "hidden");
                dojo.byId("phrase").focus();
            },

            _unsubscribe: function() {
                if (room._chatSubscription) {
                    cometd.unsubscribe(room._chatSubscription);
                }
                room._chatSubscription = null;
                if (room._membersSubscription) {
                    cometd.unsubscribe(room._membersSubscription);
                }
                room._membersSubscription = null;
            },

            _subscribe: function() {
                room._chatSubscription = cometd.subscribe('/chat/demo', room.receive);
                room._membersSubscription = cometd.subscribe('/members/demo', room.members);
            },

            leave: function() {
                cometd.batch(function() {
                    cometd.publish("/chat/demo", {
                        user: room._username,
                        membership: 'leave',
                        chat: room._username + " has left"
                    });
                    room._unsubscribe();
                });
                cometd.disconnect();

                // switch the input form
                dojo.removeClass("join", "hidden");
                dojo.addClass("joined", "hidden");

                dojo.byId("username").focus();
                dojo.byId('members').innerHTML = "";

                room._username = null;
                room._lastUser = null;
                room._disconnecting = true;
            },

            chat: function() {
                var text = dojo.byId('phrase').value;
                dojo.byId('phrase').value = '';
                if (!text || !text.length) return;

                var colons = text.indexOf("::");
                if (colons > 0) {
                    cometd.publish("/service/privatechat", {
                        room: "/chat/demo", // This should be replaced by the room name
                        user: room._username,
                        chat: text.substring(colons + 2),
                        peer: text.substring(0, colons)
                    });
                } else {
                    cometd.publish("/chat/demo", {
                        user: room._username,
                        chat: text
                    });
                }
            },

            receive: function(message) {
                var fromUser = message.data.user;
                var membership = message.data.join || message.data.leave;
                var text = message.data.chat;

                if (!membership && fromUser === room._lastUser) {
                    fromUser = "...";
                } else {
                    room._lastUser = fromUser;
                    fromUser += ":";
                }

                var chat = dojo.byId('chat');
                if (membership) {
                    chat.innerHTML += "<span class=\"membership\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span></span><br/>";
                    room._lastUser = null;
                } else if (message.data.scope === "private") {
                    chat.innerHTML += "<span class=\"private\"><span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">[private]&nbsp;" + text + "</span></span><br/>";
                } else {
                    chat.innerHTML += "<span class=\"from\">" + fromUser + "&nbsp;</span><span class=\"text\">" + text + "</span><br/>";
                }

                chat.scrollTop = chat.scrollHeight - chat.clientHeight;
            },

            members: function(message) {
                var members = dojo.byId('members');
                var list = "";
                for (var i in message.data) {
                    list += message.data[i] + "<br/>";
                }
                members.innerHTML = list;
            },

            _connectionInitialized: function() {
                // first time connection for this client, so subscribe and tell everybody.
                cometd.batch(function() {
                    room._subscribe();
                    cometd.publish('/chat/demo', {
                        user: room._username,
                        membership: 'join',
                        chat: room._username + ' has joined'
                    });
                });
            },

            _connectionEstablished: function() {
                // connection establish (maybe not for first time), so just
                // tell local user and update membership
                room.receive({
                    data: {
                        user: 'system',
                        chat: 'Connection to Server Opened'
                    }
                });
                cometd.publish('/service/members', {
                    user: room._username,
                    room: '/chat/demo'
                });
            },

            _connectionBroken: function() {
                room.receive({
                    data: {
                        user: 'system',
                        chat: 'Connection to Server Broken'
                    }
                });
                dojo.byId('members').innerHTML = "";
            },

            _connectionClosed: function() {
                room.receive({
                    data: {
                        user: 'system',
                        chat: 'Connection to Server Closed'
                    }
                });
            },

            _metaHandshake: function(message) {
                if (message.successful) {
                    room._connectionInitialized();
                }
            },

            _metaConnect: function(message) {
                if (room._disconnecting) {
                    room._connected = false;
                    room._connectionClosed();
                } else {
                    var wasConnected = room._connected;
                    room._connected = message.successful === true;
                    if (!wasConnected && room._connected) {
                        room._connectionEstablished();
                    } else if (wasConnected && !room._connected) {
                        room._connectionBroken();
                    }
                }
            }
        };

        cometd.addListener("/meta/handshake", room, room._metaHandshake);
        cometd.addListener("/meta/connect", room, room._metaConnect);
        dojo.addOnLoad(room, "_init");
        dojo.addOnUnload(function() {
            if (room._username) {
                cometd.reload();
                window.sessionStorage.setItem(stateKey, JSON.stringify({
                    username: room._username
                }));
            }
            else {
                cometd.disconnect();
            }
        });
    });
