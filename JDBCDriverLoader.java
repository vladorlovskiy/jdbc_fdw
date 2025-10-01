/*-------------------------------------------------------------------------
 *
 *		  foreign-data wrapper for JDBC
 *
 * Copyright (c) 2012, PostgreSQL Global Development Group
 *
 * This software is released under the PostgreSQL Licence
 *
 * Author: Atri Sharma <atri.jiit@gmail.com>
 *
 * IDENTIFICATION
 *		  jdbc_fdw/JDBCDriverLoader.java
 *
 *-------------------------------------------------------------------------
 */

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;

public class JDBCDriverLoader extends URLClassLoader
{

/*
 * JDBCDriverLoader
 *		Constructor of JDBCDriverLoader class.
 */
	public 
	JDBCDriverLoader(URL[] path)
	{
		super(path);
	}

/*
 * appendURL
 *		Adds a path to the path of the loader.
 */
	public void 
	appendURL(URL url)
	{
		addURL(url);
	}

/*
 * CheckIfClassIsLoaded
 *		Checks if a class of given classname has been loaded by the loader or not.
 */
	public Class<?>
	CheckIfClassIsLoaded(String ClassName)
	{
		return findLoadedClass(ClassName);
	}
}
