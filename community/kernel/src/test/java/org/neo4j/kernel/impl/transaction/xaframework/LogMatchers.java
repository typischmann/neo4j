/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.xaframework;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.transaction.xa.Xid;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.impl.nioneo.xa.CommandReaderFactory;
import org.neo4j.kernel.impl.nioneo.xa.LogDeserializer;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;
import org.neo4j.kernel.impl.transaction.xaframework.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.xaframework.log.entry.LogEntryCommand;
import org.neo4j.kernel.impl.transaction.xaframework.log.entry.LogEntryStart;
import org.neo4j.kernel.impl.transaction.xaframework.log.entry.OnePhaseCommit;
import org.neo4j.kernel.impl.transaction.xaframework.log.entry.VersionAwareLogEntryReader;

import static org.neo4j.kernel.impl.util.Cursors.iterable;

/**
 * A set of hamcrest matchers for asserting logical logs look in certain ways.
 * Please expand as necessary.
 *
 * Please note: Matching specific commands is done by matchers found in
 * {@link org.neo4j.kernel.impl.nioneo.xa.CommandMatchers}.
 */
public class LogMatchers
{
    public static ResourceIterable<LogEntry> logEntries( FileSystemAbstraction fileSystem, String logPath ) throws IOException
    {
        StoreChannel fileChannel = fileSystem.open( new File( logPath ), "r" );
        ByteBuffer buffer = ByteBuffer.allocateDirect( 9 + Xid.MAXGTRIDSIZE + Xid.MAXBQUALSIZE * 10 );

        // Always a header
        VersionAwareLogEntryReader.readLogHeader( buffer, fileChannel, true );

        // Read all log entries
        LogDeserializer deserializer = new LogDeserializer( CommandReaderFactory.DEFAULT );

        ReadableLogChannel logChannel = new ReadAheadLogChannel(
                new PhysicalLogVersionedStoreChannel( fileChannel ), LogVersionBridge.NO_MORE_CHANNELS, 4096 );
        return iterable( deserializer.logEntries( logChannel ) );
    }

    public static ResourceIterable<LogEntry> logEntries( FileSystemAbstraction fileSystem, File file ) throws IOException
    {
        return logEntries( fileSystem, file.getPath() );
    }

    public static Matcher<ResourceIterable<LogEntry>> containsExactly( final Matcher<? extends LogEntry>... matchers )
    {
        return new TypeSafeMatcher<ResourceIterable<LogEntry>>()
        {
            @Override
            public boolean matchesSafely( ResourceIterable<LogEntry> item )
            {
                try (ResourceIterator<LogEntry> actualEntries = item.iterator())
                {
                    for ( Matcher<? extends LogEntry> matcher : matchers )
                    {
                        if ( actualEntries.hasNext() )
                        {
                            LogEntry next = actualEntries.next();
                            if ( !matcher.matches( next ) )
                            {
                                // Wrong!
                                return false;
                            }
                        }
                        else
                        {
                            // Too few actual entries!
                            return false;
                        }
                    }

                    if ( actualEntries.hasNext() )
                    {
                        // Too many actual entries!
                        return false;
                    }

                    // All good in the hood :)
                    return true;
                }
            }

            @Override
            public void describeTo( Description description )
            {
                for ( Matcher<? extends LogEntry> matcher : matchers )
                {
                    description.appendDescriptionOf( matcher ).appendText( ",\n" );
                }
            }
        };
    }

    public static Matcher<? extends LogEntry> startEntry( final int masterId, final int localId )
    {
        return new TypeSafeMatcher<LogEntryStart>()
        {
            @Override
            public boolean matchesSafely( LogEntryStart entry )
            {
                return entry != null && entry.getMasterId() == masterId
                        && entry.getLocalId() == localId;
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( "Start[" + "xid=<Any Xid>,master=" + masterId + ",me=" + localId
                        + ",time=<Any Date>]" );
            }
        };
    }

    public static Matcher<? extends LogEntry> commitEntry( final long txId )
    {
        return new TypeSafeMatcher<OnePhaseCommit>()
        {
            @Override
            public boolean matchesSafely( OnePhaseCommit onePC )
            {
                return onePC != null && onePC.getTxId() == txId;
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( String.format( "Commit[txId=%d, <Any Date>]", txId ) );
            }
        };
    }

    public static Matcher<? extends LogEntry> commandEntry( final long key,
            final Class<? extends Command> commandClass )
    {
        return new TypeSafeMatcher<LogEntryCommand>()
        {
            @Override
            public boolean matchesSafely( LogEntryCommand commandEntry )
            {
                if ( commandEntry == null )
                {
                    return false;
                }

                Command command = commandEntry.getXaCommand();
                return command.getKey() == key &&
                       command.getClass().equals( commandClass );
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( String.format( "Command[key=%d, cls=%s]", key, commandClass.getSimpleName() ) );
            }
        };
    }
}
