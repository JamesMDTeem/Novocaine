/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.lang.ref.*;

public class WeakHashedSet<E> extends AbstractSet<E> {
    private static final double loadfac = 0.5;
    private final ReferenceQueue<E> cleanq = new ReferenceQueue<>();
    public final Hash<? super E> hash;
    private Ref<E>[] tab;
    private int sz;

    static class Ref<T> extends WeakReference<T> {
	final int hash;

	Ref(T ob, ReferenceQueue<T> q) {
	    super(ob, q);
	    this.hash = ob.hashCode();
	}
    }

    public WeakHashedSet(Hash<? super E> hash) {
	this.hash = hash;
	clear();
    }

    @SuppressWarnings("unchecked")
    public void clear() {
	this.tab = (Ref<E>[])new Ref[32];
	this.sz = 0;
    }

    public int size() {
	return(sz);
    }

    public boolean isEmpty() {
	return(sz == 0);
    }

    private int nextidx(Ref[] tab, int idx) {
	return((idx + 1) & (tab.length - 1));
    }

    /* The slot a hash starts probing from. Mixed first (murmur3's finalizer): the table is
     * linear-probed, so raw hashCode() low bits that cluster make runs whose every step is a full
     * equals(). RenderTree.DepInfo's hash is a polynomial over its states' hashes, and stall
     * captures (2026-09-12) caught its intern() 440 ms to 2.9 s deep in findidx/DepInfo.equals -
     * a probe run, not the lock. Bijective, so it can only spread what was there. */
    private static int slot(int h, int len) {
	h ^= h >>> 16;
	h *= 0x85ebca6b;
	h ^= h >>> 13;
	h *= 0xc2b2ae35;
	h ^= h >>> 16;
	return(h & (len - 1));
    }

    private int hashidx(Ref[] tab, E el) {
	return(slot(hash.hash(el), tab.length));
    }

    @SuppressWarnings("unchecked")
    private int findidx(Ref<E>[] tab, Object e) {
	E el = (E)e;
	int idx = hashidx(tab, el);
	while(true) {
	    Ref<E> cref = tab[idx];
	    if(cref == null)
		return(-1);
	    E cur = cref.get();
	    if((cur != null) && hash.equal(el, cur))
		return(idx);
	    idx = nextidx(tab, idx);
	}
    }

    private int refidx(Ref<E>[] tab, Ref ref) {
	int idx = slot(ref.hash, tab.length);
	while(true) {
	    Ref cur = tab[idx];
	    if(cur == null)
		return(-1);
	    if(cur == ref)
		return(idx);
	    idx = nextidx(tab, idx);
	}
    }

    /* How many dead references one clean() may reap (from brodgar-io-client f415785dc).
     * Unbounded, a single intern() paid for everything the last GC cycle freed, with the
     * CALLER's monitor held - the shape of a frame that freezes once every few seconds. What is
     * left stays queued for the next call; a dead entry still in the table costs only its slot,
     * since every read tests Ref.get() for null. One add() enqueues at most one future corpse
     * and reaps up to cleanmax, so the drain keeps up on its own. */
    private static final int cleanmax = 64;

    private void clean() {
	Ref<E>[] tab = this.tab;
	Reference<? extends E> ref;
	for(int n = 0; (n < cleanmax) && ((ref = cleanq.poll()) != null); n++) {
	    Ref rr = (Ref)ref;
	    int idx = refidx(tab, rr);
	    if(idx < 0)
		throw(new ConcurrentModificationException());
	    remove(tab, idx);
	}
	ckshrink();
    }

    private void remove(Ref<E>[] tab, int idx) {
	tab[idx] = null;
	for(int nx = nextidx(tab, idx); tab[nx] != null; nx = nextidx(tab, nx)) {
	    int oh = slot(tab[nx].hash, tab.length);
	    if((idx < nx) ? ((oh <= idx) || (nx < oh)) : ((oh <= idx) && (nx < oh))) {
		tab[idx] = tab[nx];
		tab[nx] = null;
		idx = nx;
	    }
	}
	sz--;
    }

    public boolean remove(Object el) {
	clean();
	if(el == null)
	    return(false);
	Ref<E>[] tab = this.tab;
	int idx = findidx(tab, el);
	if(idx < 0)
	    return(false);
	remove(tab, idx);
	ckshrink();
	return(true);
    }

    private void ckshrink() {
	int nsz = tab.length;
	/* Was sz < loadfac * 0.25, a constant 0.125, so the table never shrank below its
	 * high-water mark (upstream as well). */
	while((nsz > 32) && (sz < (nsz * loadfac * 0.25)))
	    nsz >>= 1;
	if(nsz < tab.length)
	    resize(nsz);
    }

    @SuppressWarnings("unchecked")
    private void resize(int nsz) {
	Ref<E>[] ctab = this.tab;
	Ref<E>[] ntab = (Ref<E>[])new Ref[nsz];
	for(int i = 0; i < ctab.length; i++) {
	    Ref<E> cur = ctab[i];
	    if(cur != null) {
		int idx = slot(cur.hash, ntab.length);
		for(; ntab[idx] != null; idx = nextidx(ntab, idx));
		ntab[idx] = cur;
	    }
	}
	this.tab = ntab;
    }

    public boolean add(E el) {
	if(el == null)
	    throw(new NullPointerException());
	clean();
	Ref<E>[] tab = this.tab;
	int idx = hashidx(tab, el);
	while(true) {
	    Ref<E> cref = tab[idx];
	    if(cref == null)
		break;
	    E cur = cref.get();
	    if((cur != null) && hash.equal(el, cur))
		return(false);
	    idx = nextidx(tab, idx);
	}
	tab[idx] = new Ref<>(el, cleanq);
	if(++sz >= (tab.length * loadfac))
	    resize(tab.length * 2);
	return(true);
    }

    public Iterator<E> iterator() {
	return(new Iterator<E>() {
		int i = 0, lasti;
		E next = null, last = null;

		public boolean hasNext() {
		    if(next != null)
			return(true);
		    while(true) {
			if(i >= tab.length)
			    return(false);
			if((tab[i] != null) && ((next = tab[i].get()) != null))
			    return(true);
			i++;
		    }
		}

		public E next() {
		    if(!hasNext())
			throw(new NoSuchElementException());
		    last = next;
		    next = null;
		    lasti = i++;
		    return(last);
		}

		public void remove() {
		    if(last == null)
			throw(new IllegalStateException());
		    if(tab[lasti].get() != last)
			throw(new ConcurrentModificationException());
		    WeakHashedSet.this.remove(tab, lasti);
		    last = null;
		}
	    });
    }

    public boolean contains(Object el) {
	return(findidx(tab, el) >= 0);
    }

    public E find(E el) {
	Ref<E>[] tab = this.tab;
	int idx = findidx(tab, el);
	if(idx < 0)
	    return(null);
	return(tab[idx].get());
    }

    public E intern(E el) {
	E ret = find(el);
	if(ret == null)
	    add(ret = el);
	return(ret);
    }

    public String stats() {
	Map<Integer, Integer> lens = new HashMap<>();
	Ref[] tab = this.tab;
	int i;
	for(i = 0; tab[i] != null; i = nextidx(tab, i));
	for(int n = 0, c = 0; n <= tab.length; n++, i = nextidx(tab, i)) {
	    if(tab[i] == null) {
		if(c > 0) {
		    lens.compute(c, (k, v) -> (v == null) ? 1 : (v + 1));
		    c = 0;
		}
	    } else {
		c++;
	    }
	}
	List<Integer> keys = new ArrayList<>(lens.keySet());
	Collections.sort(keys);
	StringBuilder buf = new StringBuilder();
	for(Integer k : keys)
	    buf.append(String.format("%d: %d\n", k, lens.get(k)));
	return(buf.toString());
    }
}
