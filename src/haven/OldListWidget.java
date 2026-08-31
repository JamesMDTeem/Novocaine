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

public abstract class OldListWidget<T> extends Widget {
    public final int itemh;
    public T sel;
    public int selindex;

    public OldListWidget(Coord sz, int itemh) {
	super(sz);
	this.itemh = itemh;
    }

    protected abstract T listitem(int i);
    protected abstract int listitems();
    protected abstract void drawitem(GOut g, T item, int i);

    public int find(T item) {
	for(int i = 0; i < listitems(); i++) {
	    if(same(listitem(i), item))
		return(i);
	}
	return(-1);
    }

    /* By VALUE, not by identity.
     *
     * Both lookups below compared with ==, which works for the case they were written for - an
     * item handed back out of listitem(i) by a click - and silently fails for the one that
     * matters just as much: restoring a selection from somewhere else. A value read out of
     * preferences, parsed from a file, or built from a name is an equal object and never the
     * same object, so indexof returned -1, change() set sel to null, and the widget drew EMPTY
     * while holding a perfectly good stored value. A dropdown that forgets what it is set to
     * every time its window is reopened is indistinguishable from one that was never set.
     *
     * Identity is still checked first, so an item type whose equals() is identity - and every
     * caller that was relying on == - behaves exactly as before. */
    private static boolean same(Object a, Object b) {
	return((a == b) || ((a != null) && a.equals(b)));
    }

    /* sel takes the item out of the LIST, not the one passed in. OldListBox draws its
     * selection with `item == sel' against what listitem(i) hands back, so a selection
     * restored by value would land on the right index and still highlight no row -- half
     * a fix. The two are equal by the test above, so nothing that reads sel can tell the
     * difference, and OldDropBox (which draws sel itself) renders the same either way. */
    public void change(T item) {
	selindex = indexof(item);
	sel = (selindex != -1) ? listitem(selindex) : null;
    }

    public void change(int index) {
        int count = listitems();
        if (index >= 0 && index < count) {
            selindex = index;
            sel = listitem(index);
        } else {
            selindex = -1;
            sel = null;
        }
    }
    public void change2(T item) {
        this.sel = item;
    }

    public int indexof(T item) {
	for(int i = 0; i < listitems(); i++) {
	    if(same(listitem(i), item))
		return(i);
	}
	return(-1);
    }
}
