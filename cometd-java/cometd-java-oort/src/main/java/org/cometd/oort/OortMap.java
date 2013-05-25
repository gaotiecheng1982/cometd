/*
 * Copyright (c) 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.oort;

import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;

public class OortMap<K, V> extends OortObject<ConcurrentMap<K, V>>
{
    public static final String TYPE_FIELD_ENTRY_VALUE = "oort.map.entry";
    public static final String ACTION_FIELD_PUT_VALUE = "oort.map.put";
    public static final String ACTION_FIELD_REMOVE_VALUE = "oort.map.remove";
    private static final String KEY_FIELD = "oort.map.key";
    private static final String VALUE_FIELD = "oort.map.value";

    private final List<EntryListener<K, V>> listeners = new CopyOnWriteArrayList<EntryListener<K, V>>();

    public OortMap(Oort oort, String name, Factory<ConcurrentMap<K, V>> factory)
    {
        super(oort, name, factory);
    }

    public void addEntryListener(EntryListener<K, V> listener)
    {
        listeners.add(listener);
    }

    public void removeEntryListener(EntryListener<K, V> listener)
    {
        listeners.remove(listener);
    }

    public List<EntryListener<K, V>> getEntryListeners()
    {
        return listeners;
    }

    public V putAndShare(K key, V value)
    {
        V result = getLocal().put(key, value);
        sharePut(key, value);
        return result;
    }

    protected void sharePut(K key, V value)
    {
        ConcurrentMap<K, V> map = getLocal();
        if (!map.containsKey(key))
            throw new IllegalArgumentException("Key " + key + " is not present in " + map);

        Map<String, Object> entry = new HashMap<String, Object>(2);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);
        String oortURL = getOort().getURL();
        Info<List<Map.Entry<K, V>>> info = new Info<List<Map.Entry<K, V>>>(oortURL, 5);
        info.put(Info.OORT_URL_FIELD, oortURL);
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, entry);
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_VALUE);

        logger.debug("Sharing put map entry info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), info, null);
    }

    public V removeAndShare(K key)
    {
        V value = getLocal().remove(key);
        shareRemove(key, value);
        return value;
    }

    protected void shareRemove(K key, V value)
    {
        Map<String, Object> entry = new HashMap<String, Object>(1);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);
        String oortURL = getOort().getURL();
        Info<List<Map.Entry<K, V>>> info = new Info<List<Map.Entry<K, V>>>(oortURL, 5);
        info.put(Info.OORT_URL_FIELD, oortURL);
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, entry);
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        logger.debug("Sharing remove map entry info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), info, null);
    }

    public V find(K key)
    {
        for (Info<ConcurrentMap<K, V>> info : this)
        {
            V result = info.getObject().get(key);
            if (result != null)
                return result;
        }
        return null;
    }

    public Info<ConcurrentMap<K, V>> findInfoByKey(K key)
    {
        for (Info<ConcurrentMap<K, V>> info : this)
        {
            if (info.getObject().get(key) != null)
                return info;
        }
        return null;
    }

    public ConcurrentMap<K, V> removeAll(Info<ConcurrentMap<K, V>> info1, Info<ConcurrentMap<K, V>> info2)
    {
        ConcurrentMap<K, V> result = new ConcurrentHashMap<K, V>();
        if (info1 != null)
        {
            result.putAll(info1.getObject());
            if (info2 != null)
                result.keySet().removeAll(info2.getObject().keySet());
        }
        return result;
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if (TYPE_FIELD_ENTRY_VALUE.equals(data.get(Info.TYPE_FIELD)))
        {
            String remoteOortURL = (String)data.get(Info.OORT_URL_FIELD);
            Info<ConcurrentMap<K, V>> info = getInfo(remoteOortURL);
            if (info != null)
            {
                ConcurrentMap<K, V> map = info.getObject();

                // Handle entry
                Map<String, Object> object = (Map<String, Object>)data.get(Info.OBJECT_FIELD);
                Entry<K, V> entry = new Entry<K, V>((K)object.get(KEY_FIELD), (V)object.get(VALUE_FIELD));

                String action = (String)data.get(Info.ACTION_FIELD);
                if (ACTION_FIELD_PUT_VALUE.equals(action))
                {
                    if (!info.isLocal())
                        map.put(entry.getKey(), entry.getValue());
                    notifyEntryPut(info, entry);
                }
                else if (ACTION_FIELD_REMOVE_VALUE.equals(action))
                {
                    if (!info.isLocal())
                        map.remove(entry.getKey());
                    notifyEntryRemoved(info, entry);
                }
            }
            else
            {
                logger.debug("Could not find info for {}", remoteOortURL);
            }
        }
        else
        {
            super.onObject(data);
        }
    }

    private void notifyEntryPut(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> entry)
    {
        for (EntryListener<K, V> listener : getEntryListeners())
        {
            try
            {
                listener.onPut(info, entry);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyEntryRemoved(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> elements)
    {
        for (EntryListener<K, V> listener : getEntryListeners())
        {
            try
            {
                listener.onRemoved(info, elements);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    public interface EntryListener<K, V> extends EventListener
    {
        public void onPut(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> entry);

        public void onRemoved(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> entry);

        public static class Adapter<K, V> implements EntryListener<K, V>
        {
            public void onPut(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> entry)
            {
            }

            public void onRemoved(Info<ConcurrentMap<K, V>> info, Map.Entry<K, V> entry)
            {
            }
        }
    }

    public static class DeltaListener<K, V> implements Listener<ConcurrentMap<K, V>>
    {
        private final OortMap<K, V> oortMap;

        public DeltaListener(OortMap<K, V> oortMap)
        {
            this.oortMap = oortMap;
        }

        public void onUpdated(Info<ConcurrentMap<K, V>> oldInfo, Info<ConcurrentMap<K, V>> newInfo)
        {
            ConcurrentMap<K, V> added = oortMap.removeAll(newInfo, oldInfo);
            ConcurrentMap<K, V> removed = oortMap.removeAll(oldInfo, newInfo);
            for (Map.Entry<K, V> entry : added.entrySet())
                oortMap.notifyEntryPut(newInfo, entry);
            for (Map.Entry<K, V> entry : removed.entrySet())
                oortMap.notifyEntryRemoved(newInfo, entry);
        }

        public void onRemoved(Info<ConcurrentMap<K, V>> info)
        {
            for (Map.Entry<K, V> entry : info.getObject().entrySet())
                oortMap.notifyEntryRemoved(info, entry);
        }
    }

    private static class Entry<K, V> implements Map.Entry<K, V>
    {
        private final K key;
        private final V value;

        public Entry(K key, V value)
        {
            this.key = key;
            this.value = value;
        }

        public K getKey()
        {
            return key;
        }

        public V getValue()
        {
            return value;
        }

        public V setValue(V value)
        {
            throw new UnsupportedOperationException();
        }
    }
}
