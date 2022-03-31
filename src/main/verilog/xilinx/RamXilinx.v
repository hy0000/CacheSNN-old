
module Ram_1w_1rs #(
    parameter integer wordCount = 0,
    parameter integer wordWidth = 0,
    parameter clockCrossing = 1'b0,
    parameter technology = "auto",
    parameter readUnderWrite = "dontCare",
    parameter integer wrAddressWidth = 0,
    parameter integer wrDataWidth = 0,
    parameter integer wrMaskWidth = 0,
    parameter wrMaskEnable = 1'b0,
    parameter integer rdAddressWidth = 0,
    parameter integer rdDataWidth  = 0
)(
    input wr_clk,
    input wr_en,
    input [wrMaskWidth-1:0] wr_mask,
    input [wrAddressWidth-1:0] wr_addr,
    input [wrDataWidth-1:0] wr_data,
    input rd_clk,
    input rd_en,
    input [rdAddressWidth-1:0] rd_addr,
    output [rdDataWidth-1:0] rd_data
);

    reg [wrDataWidth-1:0] ram_block [(2**wrAddressWidth)-1:0];
    integer i;
    localparam COL_WIDTH = wrDataWidth/wrMaskWidth;
    always @ (posedge wr_clk) begin
        if(wr_en) begin
            for(i=0;i<wrMaskWidth;i=i+1) begin
                if(wr_mask[i]) begin
                    ram_block[wr_addr][i*COL_WIDTH +: COL_WIDTH] <= wr_data[i*COL_WIDTH +:COL_WIDTH];
                end
            end
        end
    end

    reg [rdDataWidth-1:0] ram_rd_data;
    always @ (posedge rd_clk) begin
        if(rd_en) begin
            ram_rd_data <= ram_block[rd_addr];
        end
    end
    assign rd_data = ram_rd_data;
endmodule

module Ram_1wrs #(
    parameter integer wordCount = 8,
    parameter integer wordWidth = 8,
    parameter technology = "auto",
    parameter readUnderWrite = "dontCare",
    parameter duringWrite = "dontCare",
    parameter integer maskWidth = 1,
    parameter maskEnable = 1'b0
)(
    input clk,
    input en,
    input wr,
    input [$clog2(wordCount)-1:0] addr,
    input [maskWidth-1:0] mask,
    input [wordWidth-1:0] wrData,
    output [wordWidth-1:0] rdData
);

    wire [maskWidth-1:0] wea = wr?mask:0;
    xilinx_single_port_byte_write_ram_read_first #(
        .NB_COL(maskWidth),                           // Specify number of columns (number of bytes)
        .COL_WIDTH(8),                        // Specify column width (byte width, typically 8 or 9)
        .RAM_DEPTH(wordCount),                     // Specify RAM depth (number of entries)
        .RAM_PERFORMANCE("HIGH_PERFORMANCE"), // Select "HIGH_PERFORMANCE" or "LOW_LATENCY"
        .INIT_FILE("")                        // Specify name/location of RAM initialization file if using one (leave blank if not)
    ) ramInst (
        .addra(addr),     // Address bus, width determined from RAM_DEPTH
        .dina(wrData),       // RAM input data, width determined from NB_COL*COL_WIDTH
        .clka(clk),       // Clock
        .wea(wea),         // Byte-write enable, width determined from NB_COL
        .ena(en),         // RAM Enable, for additional power savings, disable port when not in use
        .rsta(1'b0),       // Output reset (does not affect memory contents)
        .regcea(!wr),    // Output register enable
        .douta(rdData)      // RAM output data, width determined from NB_COL*COL_WIDTH
    );
endmodule

module xilinx_single_port_byte_write_ram_read_first #(
    parameter NB_COL = 4,                           // Specify number of columns (number of bytes)
    parameter COL_WIDTH = 9,                        // Specify column width (byte width, typically 8 or 9)
    parameter RAM_DEPTH = 1024,                     // Specify RAM depth (number of entries)
    parameter RAM_PERFORMANCE = "HIGH_PERFORMANCE", // Select "HIGH_PERFORMANCE" or "LOW_LATENCY"
    parameter INIT_FILE = ""                        // Specify name/location of RAM initialization file if using one (leave blank if not)
) (
    input [clogb2(RAM_DEPTH-1)-1:0] addra,  // Address bus, width determined from RAM_DEPTH
    input [(NB_COL*COL_WIDTH)-1:0] dina,  // RAM input data
    input clka,                           // Clock
    input [NB_COL-1:0] wea,               // Byte-write enable
    input ena,                            // RAM Enable, for additional power savings, disable port when not in use
    input rsta,                           // Output reset (does not affect memory contents)
    input regcea,                         // Output register enable
    output [(NB_COL*COL_WIDTH)-1:0] douta // RAM output data
);

    reg [(NB_COL*COL_WIDTH)-1:0] BRAM [RAM_DEPTH-1:0];
    reg [(NB_COL*COL_WIDTH)-1:0] ram_data = {(NB_COL*COL_WIDTH){1'b0}};

    // The following code either initializes the memory values to a specified file or to all zeros to match hardware
    generate
        if (INIT_FILE != "") begin: use_init_file
            initial
                $readmemh(INIT_FILE, BRAM, 0, RAM_DEPTH-1);
        end else begin: init_bram_to_zero
            integer ram_index;
            initial
                for (ram_index = 0; ram_index < RAM_DEPTH; ram_index = ram_index + 1)
                    BRAM[ram_index] = {(NB_COL*COL_WIDTH){1'b0}};
        end
    endgenerate

    always @(posedge clka)
        if (ena) begin
            ram_data <= BRAM[addra];
        end

    generate
        genvar i;
        for (i = 0; i < NB_COL; i = i+1) begin: byte_write
            always @(posedge clka)
                if (ena)
                    if (wea[i])
                        BRAM[addra][(i+1)*COL_WIDTH-1:i*COL_WIDTH] <= dina[(i+1)*COL_WIDTH-1:i*COL_WIDTH];
        end
    endgenerate

    //  The following code generates HIGH_PERFORMANCE (use output register) or LOW_LATENCY (no output register)
    generate
        if (RAM_PERFORMANCE == "LOW_LATENCY") begin: no_output_register

            // The following is a 1 clock cycle read latency at the cost of a longer clock-to-out timing
            assign douta = ram_data;

        end else begin: output_register

            // The following is a 2 clock cycle read latency with improve clock-to-out timing

            reg [(NB_COL*COL_WIDTH)-1:0] douta_reg = {(NB_COL*COL_WIDTH){1'b0}};

            always @(posedge clka)
                if (rsta)
                    douta_reg <= {(NB_COL*COL_WIDTH){1'b0}};
                else if (regcea)
                    douta_reg <= ram_data;

            assign douta = douta_reg;

        end
    endgenerate

    //  The following function calculates the address width based on specified RAM depth
    function integer clogb2;
        input integer depth;
        for (clogb2=0; depth>0; clogb2=clogb2+1)
            depth = depth >> 1;
    endfunction

endmodule