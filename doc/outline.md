# Trivial Outline

## Sliding Window

### Client-side

- main thread receives packets from server and sends acks
- when packets are received, they are immediately sent to the window-agent
- keep listening for packets until either window-size packets have been
  received, or timeout has been reached. when that limit has been reached,
  send `flush-packets` to the agent

- window-agent
  - has a window-map
  - two functions for interacting with it
```
{:last-block #,
 :packets    {block# -> decoded packet}
}
```

- `(fn handle-packet [packet] (fn [window-map] ...))`
  - decodes a packet, and puts it in the window map
- `(fn flush-packets []
     (fn [window-map]
       (assoc window-map :last-block (appropriate-block (:last-block window-map)
                                                        (:packets window-map))
                         :packets {})))`
  - alters :last-block to the appropriate number (the greatest number in
    window-map which is comes sequentially after :last-block without a missing
    block
  - empties the :packets map, after doing this

- write a function which does something like this:
```
(do
  (send-off window-agent flush-packets)
  (await window-agent)
  (let [block (:last-block @window-agent)]
    (send-ack block)
    (recur block ...)))
```