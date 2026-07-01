package dev.babbaj.pathfinder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NetherPathfinder {

    // How the raytracer will treat chunks that aren't actually observed.
    // When chunk generation is used the caller must synchronize calls that read from the cache with calls that may mutate the cache.
    // No synchronization is done within the jni code
    public static int CACHE_MISS_GENERATE = 0;
    public static int CACHE_MISS_AIR = 1;
    public static int CACHE_MISS_SOLID = 2;

    public static int DIMENSION_OVERWORLD = 0;
    public static int DIMENSION_NETHER = 1;
    public static int DIMENSION_END = 2;

    // pass true to use the custom chunk allocator that will reduce memory usage and maybe be faster. false to just use new/delete
    // this is only supported on systems with 4k pages
    public static native long newContext(long seed, String baritoneCacheDirCanBeNull, int dimension, int maxHeight, boolean allocator);
    public static native void freeContext(long pointer);

    /*
    from BlockStateContainer
    private static int getIndex(int x, int y, int z)
    {
        return y << 8 | z << 4 | x;
    }

    chunkX and chunkZ are chunk coords, not block coords.
    Array length must be exactly 16 * 16 * 256 for the Nether dimension.
    */
    public static native void insertChunkData(long context, int chunkX, int chunkZ, boolean[] data);

    public static native long allocateAndInsertChunk(long context, int x, int z);

    // do not write to the chunk this returns
    public static native long getChunkOrDefault(long context, int x, int z, boolean solid);

    public static native long getChunk(long context, int x, int z);

    // returns true if the chunk existed and the change was made
    public static native boolean setChunkState(long context, int x, int z, boolean fromJava);

    public static native boolean hasChunkFromJava(long context, int x, int z);

    public static native void cullFarChunks(long context, int chunkX, int chunkZ, int maxDistanceBlocks);

    public static native PathSegment pathFind(long context, int x1, int y1, int z1, int x2, int y2, int z2, boolean atLeastX4, boolean refine, int failTimeoutInMillis, boolean defaultAirElseGenerate, double fakeChunkCost);

    public static native boolean cancel(long context);

    private static final boolean IS_LOADED;

    public static boolean isThisSystemSupported() {
        return IS_LOADED;
    }

    private static void tryLoadLibrary() throws IOException {
        final String resourcePath = "/natives/nether_pathfinder-x86_64.dll";
        try (InputStream in = NetherPathfinder.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            final Path tempFile = Files.createTempFile("nether_pathfinder", ".dll");
            try {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                System.load(tempFile.toAbsolutePath().toString());
            } finally {
                try {
                    Files.delete(tempFile);
                } catch (IOException ignored) {
                    tempFile.toFile().deleteOnExit();
                }
            }
        }
    }

    static {
        boolean loaded = false;
        try {
            tryLoadLibrary();
            System.out.println("[nether-pathfinder] Loaded shared library");
            loaded = true;
        } catch (Throwable e) {
            System.err.println("[nether-pathfinder] Failed to load shared library");
            e.printStackTrace();
        }
        IS_LOADED = loaded;
    }
}
