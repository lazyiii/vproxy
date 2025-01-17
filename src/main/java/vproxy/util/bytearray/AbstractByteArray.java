package vproxy.util.bytearray;

import vproxy.util.ByteArray;

import java.nio.charset.StandardCharsets;

public abstract class AbstractByteArray implements ByteArray {
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ByteArray))
            return false;
        ByteArray o = (ByteArray) obj;

        int len = this.length();

        if (len != o.length())
            return false;

        for (int i = 0; i < len; ++i) {
            if (this.get(i) != o.get(i))
                return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return new String(toJavaArray(), StandardCharsets.UTF_8);
    }
}
