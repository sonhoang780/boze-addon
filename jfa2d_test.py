def jfa2d(size, passes, target_x, target_y):
    buf = {}
    for y in range(size):
        for x in range(size):
            if 30 <= x <= 70 and 30 <= y <= 70:
                buf[(x,y)] = (0,0)
            else:
                buf[(x,y)] = None

    for p in passes:
        new_buf = {}
        for y in range(size):
            for x in range(size):
                best_dist = 999999
                best_offset = None
                for dy in [-p, 0, p]:
                    for dx in [-p, 0, p]:
                        sx = x + dx
                        sy = y + dy
                        if 0 <= sx < size and 0 <= sy < size and buf[(sx,sy)] is not None:
                            ox, oy = buf[(sx,sy)]
                            dist = (ox + dx)**2 + (oy + dy)**2
                            if dist < best_dist:
                                best_dist = dist
                                best_offset = (ox + dx, oy + dy)
                new_buf[(x,y)] = best_offset
        buf = new_buf
    
    val = buf[(target_x, target_y)]
    return (val[0]**2 + val[1]**2)**0.5 if val is not None else -1

print('32 passes large obj:', jfa2d(150, [32, 16, 8, 4, 2, 1], 70, 90))
print('16 passes large obj:', jfa2d(150, [16, 8, 4, 2, 1], 70, 90))
