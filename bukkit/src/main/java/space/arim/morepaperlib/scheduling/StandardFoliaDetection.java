/*
 * MorePaperLib
 * Copyright © 2023 Anand Beh
 *
 * MorePaperLib is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MorePaperLib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with MorePaperLib. If not, see <https://www.gnu.org/licenses/>
 * and navigate to version 3 of the GNU Lesser General Public License.
 */

package space.arim.morepaperlib.scheduling;

/**
 * Patched version of StandardFoliaDetection for Java 25 compatibility.
 *
 * <p>On Java 25, calling {@link Class#forName(String)} inside a plugin's static
 * initializer can throw {@link IllegalStateException} ("zip file closed") because
 * the {@link java.util.zip.ZipFile} backing Paper's {@code PluginClassLoader} may
 * already be closed by the time the static block executes.  The upstream version
 * only catches {@link ClassNotFoundException}, so the exception escapes and the
 * class is permanently poisoned, breaking all scheduler calls.
 *
 * <p>This replacement adds a {@code Throwable} catch that retries the lookup
 * through the server class loader (which is not subject to the plugin-JAR
 * lifecycle), so Folia detection works correctly on all supported JVM versions.
 */
final class StandardFoliaDetection implements FoliaDetection {

    private static final boolean IS_FOLIA;
    static final StandardFoliaDetection INSTANCE = new StandardFoliaDetection();

    static {
        boolean isFolia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException ignored) {
            isFolia = false;
        } catch (Throwable ignored) {
            // Java 25+: plugin class loader's ZipFile may be closed at static-init time.
            // Retry via the server class loader, which remains open for the server lifetime.
            isFolia = detectViaServerClassLoader();
        }
        IS_FOLIA = isFolia;
    }

    private static boolean detectViaServerClassLoader() {
        try {
            ClassLoader serverClassLoader = org.bukkit.Bukkit.class.getClassLoader();
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, serverClassLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private StandardFoliaDetection() {}

    @Override
    public boolean isUsingFolia() {
        return IS_FOLIA;
    }

}
