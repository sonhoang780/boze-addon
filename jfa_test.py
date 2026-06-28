def jfa(size, passes):
    buf = [(0 if 40 <= i <= 60 else None) for i in range(size)]
    for p in passes:
        new_buf = list(buf)
        for i in range(size):
            best_dist = 999999
            best_offset = None
            for offset in [-p, 0, p]:
                sample = i + offset
                if 0 <= sample < size and buf[sample] is not None:
                    dist = abs(buf[sample] + offset)
                    if dist < best_dist:
                        best_dist = dist
                        best_offset = buf[sample] + offset
            if best_offset is not None:
                new_buf[i] = best_offset
        buf = new_buf
    return [abs(x) if x is not None else -1 for x in buf]

print('32 passes:', jfa(120, [32, 16, 8, 4, 2, 1])[60:110])
print('16 passes:', jfa(120, [16, 8, 4, 2, 1])[60:110])
