package boot;

import java.net.URL;
import java.net.URLClassLoader;

// Allows us to have a modifiable ClassLoader without having to call
// .setAccessible on URLClassLoader.addURL(), since that's not allowed
// by default under Java 9
public class AddableClassLoader extends URLClassLoader {
    public AddableClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent); }
    
    public void addURL(URL url) {
	super.addURL(url); }}
