# Trivial Outline

## Sliding Window

### Client-side

- main thread receives packets from server and sends acks
- when packets are received, they are immediately sent to the window-agent
- keep listening for packets until either window-size packets have been
  received, or timeout has been reached. when that limit has been reached,
  send `flush-packets` to the agent

- window-agent
```
{:last-block #,
 :packets    {block# -> decoded packet}
}
```