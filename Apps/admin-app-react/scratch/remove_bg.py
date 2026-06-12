import sys
from PIL import Image

def remove_background_floodfill(img_path, output_path):
    img = Image.open(img_path)
    img = img.convert("RGBA")
    width, height = img.size
    pixels = img.load()
    
    # Target background color from the top-left corner
    target_color = pixels[0, 0]
    print(f"Detecting background color at (0,0): {target_color}")
    
    # We want to change pixels that are similar to the target color.
    # A color distance threshold of 45 is safe to handle JPEG artifacts or slight gradients.
    threshold = 45
    
    def color_dist(c1, c2):
        return ((c1[0]-c2[0])**2 + (c1[1]-c2[1])**2 + (c1[2]-c2[2])**2)**0.5
        
    visited = set()
    # Queue initialized with the four corners
    queue = [(0, 0), (width-1, 0), (0, height-1), (width-1, height-1)]
    for q in queue:
        visited.add(q)
        
    transparent_color = (0, 0, 0, 0)
    count = 0
    
    while queue:
        x, y = queue.pop(0)
        curr_color = pixels[x, y]
        
        if color_dist(curr_color, target_color) < threshold:
            pixels[x, y] = transparent_color
            count += 1
            
            # 4-connectivity neighbors
            for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                nx, ny = x + dx, y + dy
                if 0 <= nx < width and 0 <= ny < height:
                    if (nx, ny) not in visited:
                        visited.add((nx, ny))
                        queue.append((nx, ny))
                        
    img.save(output_path, "PNG")
    print(f"Background removed using flood fill: {output_path} (modified {count} pixels)")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python remove_bg.py <input_path> <output_path>")
        sys.exit(1)
    remove_background_floodfill(sys.argv[1], sys.argv[2])
