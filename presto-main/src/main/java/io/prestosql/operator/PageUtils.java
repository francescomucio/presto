/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.operator;

import io.prestosql.spi.Page;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LazyBlock;

import java.util.function.LongConsumer;

public final class PageUtils
{
    private PageUtils()
    {
    }

    public static Page recordMaterializedBytes(Page page, LongConsumer sizeInBytesConsumer)
    {
        // account processed bytes from lazy blocks only when they are loaded
        Block[] blocks = new Block[page.getChannelCount()];
        long loadedBlocksSizeInBytes = 0;
        boolean allBlocksLoaded = true;

        for (int i = 0; i < page.getChannelCount(); ++i) {
            Block block = page.getBlock(i);
            if (block.isLoaded()) {
                loadedBlocksSizeInBytes += block.getSizeInBytes();
                blocks[i] = block;
            }
            else {
                // TODO: block might be partially loaded
                blocks[i] = new LazyBlock(page.getPositionCount(), lazyBlock -> {
                    Block loadedBlock = block.getLoadedBlock();
                    sizeInBytesConsumer.accept(loadedBlock.getSizeInBytes());
                    lazyBlock.setBlock(loadedBlock);
                });
                allBlocksLoaded = false;
            }
        }

        if (loadedBlocksSizeInBytes > 0) {
            sizeInBytesConsumer.accept(loadedBlocksSizeInBytes);
        }

        if (allBlocksLoaded) {
            return page;
        }

        return new Page(page.getPositionCount(), blocks);
    }
}
